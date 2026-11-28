package com.weworkshop.app.scene

import android.app.WallpaperColors
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.weworkshop.app.wallpaper.ColorReport
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

/**
 * Live wallpaper that renders a Wallpaper Engine scene natively with OpenGL ES 3 (libwe_scene.so).
 * Parallax follows the phone's tilt (or touch / home-screen swipe when no sensor is available).
 */
class SceneWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = SceneEngine()

    private inner class SceneEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener, SensorEventListener {
        private val prefs = SceneWallpaper.prefs(this@SceneWallpaperService)
        private val main = Handler(Looper.getMainLooper())
        private var view: GlView? = null
        private var visible = false

        /** Colours reported to the system (desktop text, status bar), refreshed once per scene. */
        @Volatile private var colors: WallpaperColors? = null

        /** Set when the drawn frame no longer matches [colors]; the GL thread clears it. */
        @Volatile private var needColors = true

        // Parallax input, -1..1 (y up). Written on the main thread, read on the GL thread.
        @Volatile private var px = 0f
        @Volatile private var py = 0f
        private var touchX = 0f
        private var touchY = 0f
        private var swipeX = 0f
        private var tiltX = 0f
        private var tiltY = 0f
        private var baseRoll = Float.NaN
        private var basePitch = Float.NaN
        private var sensors: SensorManager? = null

        @Volatile private var fpsLimit = SceneWallpaper.fps(this@SceneWallpaperService)
        @Volatile private var tiltOn = SceneWallpaper.tilt(this@SceneWallpaperService)
        @Volatile private var strength = SceneWallpaper.tiltStrength(this@SceneWallpaperService) / 100f

        private inner class GlView(ctx: Context) : GLSurfaceView(ctx) {
            override fun getHolder(): SurfaceHolder = surfaceHolder
            fun release() = onDetachedFromWindow()
        }

        private val tick = Runnable { view?.requestRender() }

        private val renderer = object : GLSurfaceView.Renderer {
            private var scene: NativeScene? = null
            private var videos: VideoTextures? = null
            private var w = 1
            private var h = 1
            private var last = 0L
            private var start = 0L
            private var loadedFrom: String? = null
            private var frames = 0

            /** Phones often drop app logs, so the last renderer state is also kept in a file for diagnosis. */
            private fun status(msg: String) {
                runCatching { File(filesDir, "scene_status.txt").appendText("${System.currentTimeMillis()} $msg" + System.lineSeparator()) }
            }

            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                // The EGL context is new (first start or after the context was lost): rebuild everything.
                dropScene()
                loadedFrom = null
                start = SystemClock.elapsedRealtime()
                last = 0L
                frames = 0
            }

            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                w = width; h = height
            }

            override fun onDrawFrame(gl: GL10?) {
                val ctx = this@SceneWallpaperService
                val path = prefs.getString(SceneWallpaper.KEY_PROJECT, null)
                if (path != loadedFrom) {
                    dropScene()
                    loadedFrom = path
                    colors = null
                    needColors = true
                    if (path != null) {
                        scene = try {
                            NativeScene.create(path, SceneWallpaper.assetsDir(ctx).takeIf { it.isDirectory }?.absolutePath)
                                .also {
                                    status("loaded $path")
                                    videos = VideoTextures.attach(ctx, it, ::status)
                                    if (!visible) videos?.pause()
                                }
                        } catch (t: Throwable) {
                            Log.e(TAG, "scene load failed: ${t.message}", t)
                            status("load failed: $path -> ${t.message}")
                            null
                        }
                    }
                }
                val s = scene
                if (s == null) {
                    android.opengl.GLES30.glClearColor(0f, 0f, 0f, 1f)
                    android.opengl.GLES30.glClear(android.opengl.GLES30.GL_COLOR_BUFFER_BIT)
                    return
                }
                val now = SystemClock.elapsedRealtime()
                val dt = if (last == 0L) 0.016f else (now - last) / 1000f
                last = now
                videos?.update()
                val animated = s.draw(w, h, (now - start) / 1000f, dt, px, py)
                if (needColors) {
                    needColors = false
                    runCatching { reportColors(w, h) }.onFailure {
                        Log.w(TAG, "colour readback failed", it)
                        status("colour readback failed: ${it.message}")
                    }
                }
                if (frames++ == 0) status("first frame ${w}x$h animated=$animated")
                // The library's "wants another frame" hint misses scenes that move only through shader time,
                // which would then freeze after one frame. Keep ticking at the frame budget while visible.
                if (visible) {
                    val frameMs = 1000L / fpsLimit.coerceAtLeast(1)
                    val spent = SystemClock.elapsedRealtime() - now
                    main.removeCallbacks(tick)
                    main.postDelayed(tick, (frameMs - spent).coerceAtLeast(0))
                    if (frames % 120 == 0) status("frames=$frames fps=$fpsLimit animated=$animated dt=${"%.3f".format(dt)}")
                }
            }

            /**
             * Reads back the frame that was just drawn and reports its colours to the system, so the
             * launcher can pick readable icon, label and status bar colours for this wallpaper.
             */
            private fun reportColors(width: Int, height: Int) {
                if (width <= 0 || height <= 0 || width.toLong() * height > 8_000_000L) return
                val buf = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
                android.opengl.GLES30.glReadPixels(
                    0, 0, width, height,
                    android.opengl.GLES30.GL_RGBA, android.opengl.GLES30.GL_UNSIGNED_BYTE, buf,
                )
                buf.rewind()
                val px = IntArray(width * height)
                buf.asIntBuffer().get(px)
                // GL hands the rows back bottom-up, so copy them into screen order.
                val straight = IntArray(px.size)
                for (y in 0 until height) System.arraycopy(px, y * width, straight, (height - 1 - y) * width, width)
                val full = Bitmap.createBitmap(straight, width, height, Bitmap.Config.ARGB_8888)
                val sample = ColorReport.scaled(full)
                if (sample !== full) full.recycle()
                val (base, top) = ColorReport.analyze(sample)
                sample.recycle()
                val dark = ColorReport.wantsDarkText(top)
                status("colours top=%.2f darkText=$dark".format(top))
                if (base == null) return
                colors = ColorReport.withTextHint(base, dark)
                main.post { runCatching { notifyColorsChanged() } }
            }

            private fun dropScene() {
                videos?.release(); videos = null
                scene?.destroy(); scene = null
            }

            fun setPlaying(on: Boolean) {
                if (on) videos?.resume() else videos?.pause()
            }

            fun release() {
                dropScene()
            }
        }

        /** Launchers and the system UI ask for these to decide text and status bar colours. */
        override fun onComputeColors(): WallpaperColors? = colors

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            colors = null
            needColors = true
            setTouchEventsEnabled(true)
            prefs.registerOnSharedPreferenceChangeListener(this)
            sensors = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            view = GlView(this@SceneWallpaperService).apply {
                setEGLContextClientVersion(3)
                setEGLConfigChooser(8, 8, 8, 8, 0, 0)
                preserveEGLContextOnPause = false
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            }
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            if (v) {
                view?.onResume()
                view?.queueEvent { renderer.setPlaying(true) }
                view?.requestRender()
                startTilt()
            } else {
                stopTilt()
                main.removeCallbacks(tick)
                view?.queueEvent { renderer.setPlaying(false) }
                view?.onPause()
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            view?.requestRender()
        }

        override fun onOffsetsChanged(xOff: Float, yOff: Float, xStep: Float, yStep: Float, xPix: Int, yPix: Int) {
            swipeX = (xOff - 0.5f) * 2f
            combine()
            view?.requestRender()
        }

        override fun onTouchEvent(e: MotionEvent) {
            if (sensors?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null && tiltOn) return
            val w = surfaceHolder.surfaceFrame.width().coerceAtLeast(1)
            val h = surfaceHolder.surfaceFrame.height().coerceAtLeast(1)
            touchX = e.x / w * 2f - 1f
            touchY = 1f - e.y / h * 2f
            combine()
            view?.requestRender()
        }

        private fun combine() {
            px = ((touchX + tiltX + swipeX * 0.5f) * strength).coerceIn(-1f, 1f)
            py = ((touchY + tiltY) * strength).coerceIn(-1f, 1f)
        }

        private fun startTilt() {
            if (!tiltOn) return
            val sm = sensors ?: return
            val s = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: return
            baseRoll = Float.NaN
            sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        }

        private fun stopTilt() {
            sensors?.unregisterListener(this)
        }

        private val rot = FloatArray(9)
        private val ori = FloatArray(3)

        override fun onSensorChanged(ev: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rot, ev.values)
            SensorManager.getOrientation(rot, ori)
            val pitch = ori[1]
            val roll = ori[2]
            // The pose when the wallpaper became visible is the neutral position; follow it slowly so
            // lying down or walking doesn't pin the parallax to an edge.
            if (basePitch.isNaN()) { basePitch = pitch; baseRoll = roll }
            basePitch += (pitch - basePitch) * 0.002f
            baseRoll += (roll - baseRoll) * 0.002f
            val range = 0.45f // radians (about 26 degrees) maps to the full parallax range
            val nx = ((roll - baseRoll) / range).coerceIn(-1f, 1f)
            val ny = (-(pitch - basePitch) / range).coerceIn(-1f, 1f)
            if (abs(nx - tiltX) > 0.003f || abs(ny - tiltY) > 0.003f) {
                tiltX = nx; tiltY = ny
                combine()
                view?.requestRender()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        override fun onSharedPreferenceChanged(p: SharedPreferences, key: String?) {
            when (key) {
                SceneWallpaper.KEY_FPS -> fpsLimit = SceneWallpaper.fps(this@SceneWallpaperService)
                SceneWallpaper.KEY_TILT_STRENGTH -> strength = SceneWallpaper.tiltStrength(this@SceneWallpaperService) / 100f
                SceneWallpaper.KEY_TILT -> {
                    tiltOn = SceneWallpaper.tilt(this@SceneWallpaperService)
                    stopTilt(); tiltX = 0f; tiltY = 0f
                    if (visible) startTilt()
                    combine()
                }
            }
            view?.requestRender()
        }

        override fun onDestroy() {
            super.onDestroy()
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            stopTilt()
            main.removeCallbacks(tick)
            // The GL thread owns the context: let it tear the scene down before the thread exits.
            view?.queueEvent { renderer.release() }
            view?.release()
            view = null
        }
    }

    private companion object {
        const val TAG = "SceneWallpaper"
    }
}
