package com.weworkshop.app.wallpaper

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.audiofx.Visualizer
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.weworkshop.app.AppSettings
import java.io.ByteArrayOutputStream
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sqrt

/** Needed only so [MediaSessionManager] lets us read other apps' media sessions (user grants "notification access"). */
class MediaListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        Handler(Looper.getMainLooper()).post { MusicBridge.ensure(applicationContext) }
    }
}

data class Track(
    val title: String,
    val artist: String,
    val album: String,
    /** 0 stopped, 1 playing, 2 paused (Wallpaper Engine's numbering). */
    val state: Int,
    val durationMs: Long,
    private val positionMs: Long,
    private val stamp: Long,
    private val speed: Float,
    /** data: URL of the cover art, or null. */
    val thumbnail: String?,
    val color: Int,
) {
    fun positionNowMs(): Long =
        if (state == 1) (positionMs + ((SystemClock.elapsedRealtime() - stamp) * speed).toLong()).coerceAtMost(durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
        else positionMs
}

/**
 * Collects what music-reactive wallpapers need: a 64-band audio spectrum of the system output
 * ([Visualizer], needs RECORD_AUDIO) and the active media session's metadata (needs notification access).
 * Reference counted: consumers [acquire] while they are visible and [release] when they are not.
 * Everything is off unless [AppSettings.musicReactive] is on.
 */
object MusicBridge {
    private const val TAG = "MusicBridge"
    private val main = Handler(Looper.getMainLooper())
    private var refs = 0

    @Volatile var spectrum = FloatArray(128)
        private set
    @Volatile var track: Track? = null
        private set
    /** Incremented whenever [track]'s title / art / state changes. */
    @Volatile var version = 0
        private set

    private var visualizer: Visualizer? = null
    private var sessions: MediaSessionManager? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var controller: MediaController? = null
    private var callback: MediaController.Callback? = null

    fun hasAudioPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun hasNotificationAccess(ctx: Context) =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun acquire(ctx: Context) {
        main.post {
            refs++
            ensure(ctx.applicationContext)
        }
    }

    fun release() {
        main.post {
            if (refs > 0) refs--
            if (refs == 0) stop()
        }
    }

    /** Starts whichever sources are enabled and permitted; safe to call repeatedly (e.g. after granting access). */
    fun ensure(ctx: Context) {
        if (refs == 0) return
        if (!AppSettings.musicReactive.value) { stop(); return }
        if (visualizer == null && hasAudioPermission(ctx)) startAudio()
        if (sessionsListener == null && hasNotificationAccess(ctx)) startMedia(ctx)
    }

    private fun startAudio() {
        try {
            val v = Visualizer(0)
            v.captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vis: Visualizer?, waveform: ByteArray?, rate: Int) {}
                override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, rate: Int) {
                    if (fft != null) onFft(fft)
                }
            }, Visualizer.getMaxCaptureRate() / 2, false, true)
            v.enabled = true
            visualizer = v
        } catch (t: Throwable) {
            Log.w(TAG, "visualizer unavailable", t)
        }
    }

    private fun onFft(fft: ByteArray) {
        val n = fft.size / 2
        if (n < 8) return
        val prev = spectrum
        val out = FloatArray(128)
        for (b in 0 until 64) {
            val lo = (n - 1.0).pow(b / 64.0).toInt().coerceAtLeast(1)
            val hi = maxOf(lo + 1, (n - 1.0).pow((b + 1) / 64.0).toInt()).coerceAtMost(n)
            var m = 0f
            for (k in lo until hi) m = maxOf(m, hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat()))
            val s = maxOf(sqrt((m / 100f).coerceIn(0f, 1f)), prev[b] * 0.85f)
            out[b] = s
            out[64 + b] = s
        }
        spectrum = out
    }

    private fun startMedia(ctx: Context) {
        try {
            val msm = ctx.getSystemService(MediaSessionManager::class.java)
            val cn = ComponentName(ctx, MediaListenerService::class.java)
            val l = MediaSessionManager.OnActiveSessionsChangedListener { bind(it.orEmpty()) }
            msm.addOnActiveSessionsChangedListener(l, cn, main)
            sessions = msm
            sessionsListener = l
            bind(msm.getActiveSessions(cn))
        } catch (t: Throwable) {
            Log.w(TAG, "media sessions unavailable", t)
        }
    }

    private fun bind(list: List<MediaController>) {
        val c = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (c?.sessionToken != controller?.sessionToken) {
            unbindController()
            controller = c
            if (c != null) {
                val cb = object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) = update()
                    override fun onPlaybackStateChanged(state: PlaybackState?) = update()
                    override fun onSessionDestroyed() { bind(emptyList()) }
                }
                c.registerCallback(cb, main)
                callback = cb
            }
        }
        update()
    }

    private fun update() {
        val c = controller
        if (c == null) {
            if (track != null) { track = null; version++ }
            return
        }
        val md = c.metadata
        val ps = c.playbackState
        val state = when (ps?.state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING -> 1
            PlaybackState.STATE_PAUSED -> 2
            else -> 0
        }
        val old = track
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = (md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty()
        val album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        // Only re-encode the cover when the song changed.
        var thumb = old?.thumbnail
        var color = old?.color ?: 0
        if (old == null || old.title != title || old.artist != artist || old.album != album || thumb == null) {
            val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            if (art != null) {
                val (url, avg) = encode(art)
                thumb = url; color = avg
            } else { thumb = null; color = 0 }
        }
        val t = Track(
            title, artist, album, state,
            md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            ps?.position?.coerceAtLeast(0) ?: 0L,
            ps?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            ps?.playbackSpeed ?: 1f,
            thumb, color,
        )
        track = t
        version++
    }

    private fun encode(src: Bitmap): Pair<String, Int> {
        val scale = 256f / maxOf(src.width, src.height).coerceAtLeast(1)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true) else src
        val bos = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 80, bos)
        val avg = Bitmap.createScaledBitmap(small, 1, 1, true).getPixel(0, 0) or (0xFF shl 24)
        return "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP) to avg
    }

    private fun unbindController() {
        callback?.let { controller?.unregisterCallback(it) }
        callback = null
        controller = null
    }

    private fun stop() {
        runCatching { visualizer?.enabled = false; visualizer?.release() }
        visualizer = null
        spectrum = FloatArray(128)
        sessionsListener?.let { runCatching { sessions?.removeOnActiveSessionsChangedListener(it) } }
        sessionsListener = null
        sessions = null
        unbindController()
        if (track != null) { track = null; version++ }
    }
}
