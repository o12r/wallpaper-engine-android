package com.weworkshop.app.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.View
import android.view.ViewGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

// Offscreen capture of web wallpapers. The WebView is never attached to a window; it only
// needs to be measured/laid out and forced onto a software layer (a hardware layer only
// rasterizes the visible rect, so an offscreen view would draw blank).

/** Builds a measured, software-layer VirtualWebView of w x h and starts loading [page]. Main thread only. */
private fun makeCaptureView(ctx: Context, page: File, preset: File?, w: Int, h: Int): VirtualWebView {
    val v = VirtualWebView(ctx)
    v.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    v.measure(
        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
    )
    v.layout(0, 0, w, h)
    // Loads the page through the same local origin the live wallpaper uses; music is never start()ed.
    v.music.load(page, preset)
    return v
}

private fun teardown(v: VirtualWebView?) {
    if (v == null) return
    runCatching { v.music.stop() }
    runCatching { (v.web.parent as? ViewGroup)?.removeView(v.web) }
    runCatching { v.web.destroy() }
}

/** True when every sampled pixel is opaque pure black, i.e. nothing was drawn. */
private fun looksBlank(b: Bitmap): Boolean {
    val xs = intArrayOf(1, 3, 5, 7, 9).map { b.width * it / 10 }
    val ys = intArrayOf(1, 3, 5, 7, 9).map { b.height * it / 10 }
    for (x in xs) for (y in ys) {
        if (b.getPixel(x.coerceIn(0, b.width - 1), y.coerceIn(0, b.height - 1)) != 0xFF000000.toInt()) return false
    }
    return true
}

/** Renders the web wallpaper offscreen at w×h and returns a still, or null if nothing was drawn. */
suspend fun webStill(ctx: Context, page: File, preset: File?, w: Int, h: Int, settleMs: Long = 2500L): Bitmap? =
    withContext(Dispatchers.Main.immediate) {
        var v: VirtualWebView? = null
        var bmp: Bitmap? = null
        try {
            val outW = maxOf(2, (w / 2) * 2)
            val outH = maxOf(2, (h / 2) * 2)
            v = makeCaptureView(ctx.applicationContext, page, preset, outW, outH)
            delay(settleMs)
            val b = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            bmp = b
            v.draw(Canvas(b))
            if (looksBlank(b)) {
                b.recycle(); bmp = null
                null
            } else {
                bmp = null // ownership passes to the caller
                b
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            bmp?.recycle()
            throw e
        } catch (_: Throwable) {
            bmp?.recycle()
            null
        } finally {
            teardown(v)
        }
    }

/** Records the web wallpaper offscreen for [seconds] into [out] (H.264 MP4). Returns out on success. */
suspend fun webToMp4(
    ctx: Context, page: File, preset: File?, w: Int, h: Int, out: File,
    seconds: Int = 10, fps: Int = 30
): File? = withContext(Dispatchers.Main.immediate) {
    val outW = maxOf(2, (w / 2) * 2)
    val outH = maxOf(2, (h / 2) * 2)
    var v: VirtualWebView? = null
    var codec: MediaCodec? = null
    var muxer: MediaMuxer? = null
    var surface: android.view.Surface? = null
    var track = -1
    var muxerStarted = false
    var ok = false
    try {
        out.parentFile?.mkdirs()
        if (out.exists()) out.delete()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, minOf(20_000_000L, (outW.toLong() * outH * fps / 5)).toInt())
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec = c
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val s = c.createInputSurface()
        surface = s
        c.start()
        val m = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer = m
        val info = MediaCodec.BufferInfo()
        var written = 0L

        fun drain(end: Boolean) {
            while (true) {
                val i = c.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                when {
                    i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                    i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = m.addTrack(c.outputFormat); m.start(); muxerStarted = true
                    }
                    i >= 0 -> {
                        val buf = c.getOutputBuffer(i)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) {
                            // Surface timestamps follow wall-clock; restamp to a steady frame rate.
                            info.presentationTimeUs = written++ * 1_000_000L / fps
                            m.writeSampleData(track, buf, info)
                        }
                        c.releaseOutputBuffer(i, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        v = makeCaptureView(ctx.applicationContext, page, preset, outW, outH)
        delay(1500L) // let the page load before the first frame
        val total = seconds * fps
        val step = maxOf(1L, 1000L / fps)
        for (f in 0 until total) {
            coroutineContext.ensureActive()
            val canvas: Canvas = s.lockCanvas(null)
            try {
                v.draw(canvas)
            } finally {
                s.unlockCanvasAndPost(canvas)
            }
            drain(false)
            delay(step) // let page animations advance between frames
        }
        c.signalEndOfInputStream()
        drain(true)
        ok = written > 0
    } catch (e: kotlinx.coroutines.CancellationException) {
        runCatching { out.delete() }
        throw e
    } catch (_: Throwable) {
        ok = false
    } finally {
        teardown(v)
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { surface?.release() }
        if (muxerStarted) runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
        if (!ok) runCatching { out.delete() }
    }
    if (ok) out else null
}
