package com.weworkshop.app.scene

import android.app.Activity
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import java.io.File
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Shows a scene in a normal window (extra "project" = scene folder). Also used to test on devices without a wallpaper service. */
class ScenePreviewActivity : Activity() {
    private lateinit var view: GLSurfaceView
    @Volatile private var px = 0f
    @Volatile private var py = 0f

    private fun status(msg: String) {
        runCatching { File(filesDir, "scene_status.txt").appendText("${System.currentTimeMillis()} $msg" + System.lineSeparator()) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val project = intent.getStringExtra("project") ?: SceneWallpaper.prefs(this).getString(SceneWallpaper.KEY_PROJECT, null)
        view = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 0, 0)
            setRenderer(object : GLSurfaceView.Renderer {
                var scene: NativeScene? = null
                var w = 1; var h = 1; var start = 0L; var last = 0L; var frames = 0
                override fun onSurfaceCreated(gl: GL10?, c: EGLConfig?) {
                    start = SystemClock.elapsedRealtime()
                    scene = try {
                        project?.let { NativeScene.create(it, SceneWallpaper.assetsDir(this@ScenePreviewActivity).takeIf { d -> d.isDirectory }?.absolutePath) }
                            .also { status("preview loaded $project") }
                    } catch (t: Throwable) {
                        status("preview load failed: $project -> ${t.message}"); null
                    }
                }
                override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) { w = width; h = height }
                override fun onDrawFrame(gl: GL10?) {
                    val s = scene
                    if (s == null) { GLES30.glClearColor(0.2f, 0f, 0f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT); return }
                    val now = SystemClock.elapsedRealtime()
                    val dt = if (last == 0L) 0.016f else (now - last) / 1000f
                    last = now
                    s.draw(w, h, (now - start) / 1000f, dt, px, py)
                    if (frames++ == 0) status("preview first frame ${w}x$h | " + NativeScene.diag().lines().joinToString(" ; "))
                }
            })
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        setContentView(view)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        px = e.x / view.width.coerceAtLeast(1) * 2f - 1f
        py = 1f - e.y / view.height.coerceAtLeast(1) * 2f
        return true
    }

    override fun onResume() { super.onResume(); view.onResume() }
    override fun onPause() { view.onPause(); super.onPause() }
}
