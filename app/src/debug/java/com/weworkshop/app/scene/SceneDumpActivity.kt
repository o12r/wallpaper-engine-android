package com.weworkshop.app.scene

import android.app.Activity
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES30
import android.os.Bundle
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Debug only: renders a scene off-screen (no display needed, e.g. on WSA) and writes PNG frames.
 * adb shell am start -n com.weworkshop.app/.scene.SceneDumpActivity --es project <dir> --es assets <dir> --es out <name> --ei w 720 --ei h 1280
 * Frames land in <externalFiles>/dump/<out>_<seconds>.png; progress goes to logcat tag SceneDump.
 */
class SceneDumpActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val project = intent.getStringExtra("project") ?: return finish()
        val assets = intent.getStringExtra("assets")
        val out = intent.getStringExtra("out") ?: "scene"
        val w = intent.getIntExtra("w", 720)
        val h = intent.getIntExtra("h", 1280)
        val times = (intent.getStringExtra("times") ?: "1,4,8").split(',').map { it.trim().toFloat() }
        Thread { run(project, assets, out, w, h, times) }.start()
    }

    private fun run(project: String, assets: String?, out: String, w: Int, h: Int, times: List<Float>) {
        val dir = File(getExternalFilesDir(null), "dump").apply { mkdirs() }
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2); EGL14.eglInitialize(dpy, v, 0, v, 1)
        val cfgs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
        EGL14.eglChooseConfig(
            dpy,
            intArrayOf(EGL14.EGL_RENDERABLE_TYPE, 0x40 /* ES3 */, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 16, EGL14.EGL_NONE),
            0, cfgs, 0, 1, n, 0,
        )
        val ctx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        val surf = EGL14.eglCreatePbufferSurface(dpy, cfgs[0], intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(dpy, surf, surf, ctx)
        try {
            val scene = NativeScene.create(project, assets)
            Log.i(TAG, "diag: ${NativeScene.diag().replace('\n', '|')}")
            val videos = VideoTextures.attach(this, scene) { Log.i(TAG, it) }
            GLES30.glViewport(0, 0, w, h)
            val begin = System.nanoTime()
            var last = 0L
            val buf = ByteBuffer.allocateDirect(w * h * 4)
            val pending = times.sorted().toMutableList()
            while (pending.isNotEmpty()) {
                val now = (System.nanoTime() - begin) / 1e9f
                val dt = if (last == 0L) 0.016f else (System.nanoTime() - last) / 1e9f
                last = System.nanoTime()
                videos?.update()
                scene.draw(w, h, now, dt, 0.5f, 0.5f)
                if (now >= pending.first()) {
                    val t = pending.removeAt(0)
                    GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
                    val px = IntArray(w * h); buf.rewind()
                    val row = ByteArray(w * 4)
                    for (y in 0 until h) {
                        buf.position(y * w * 4); buf.get(row)
                        for (x in 0 until w) {
                            val o = x * 4
                            px[(h - 1 - y) * w + x] = (0xFF shl 24) or ((row[o].toInt() and 0xFF) shl 16) or
                                ((row[o + 1].toInt() and 0xFF) shl 8) or (row[o + 2].toInt() and 0xFF)
                        }
                    }
                    val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
                    val file = File(dir, "${out}_${t.toInt()}.png")
                    file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    Log.i(TAG, "wrote ${file.absolutePath}")
                }
                Thread.sleep(33)
            }
            videos?.release()
            scene.destroy()
            Log.i(TAG, "done")
        } catch (t: Throwable) {
            Log.e(TAG, "dump failed: ${t.message}", t)
        } finally {
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(dpy, surf); EGL14.eglDestroyContext(dpy, ctx)
            runOnUiThread { finish() }
        }
    }

    private companion object { const val TAG = "SceneDump" }
}
