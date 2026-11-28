package com.weworkshop.app.scene

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Feeds a scene's video textures (MP4 stored in a .tex): a muted, looping [MediaPlayer] renders into a
 * [SurfaceTexture]; every frame is copied through an external-OES shader into the scene's own 2D texture,
 * so the native renderer keeps sampling an ordinary sampler2D. All calls must run on the GL thread.
 */
class VideoTextures private constructor(private val items: List<Item>) {

    private class Item(
        val target: NativeScene.Video,
        val oes: Int,
        val st: SurfaceTexture,
        val player: MediaPlayer,
        val surface: Surface,
        val fbo: Int,
    ) {
        @Volatile var fresh = false
    }

    private var program = 0
    private var uMatrix = 0
    private var aPos = 0
    private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0)
    }
    private val matrix = FloatArray(16)

    val count: Int get() = items.size

    /** Copies the newest decoded frame of each video into its texture. Call before drawing the scene. */
    fun update() {
        if (items.none { it.fresh }) return
        if (program == 0) program = buildProgram()
        if (program == 0) return
        val vp = IntArray(4); GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, vp, 0)
        val fb = IntArray(1); GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, fb, 0)
        val blend = GLES30.glIsEnabled(GLES30.GL_BLEND)
        val depth = GLES30.glIsEnabled(GLES30.GL_DEPTH_TEST)
        val scissor = GLES30.glIsEnabled(GLES30.GL_SCISSOR_TEST)
        val cull = GLES30.glIsEnabled(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_BLEND); GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST); GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        for (item in items) {
            if (!item.fresh) continue
            item.fresh = false
            val ok = runCatching { item.st.updateTexImage() }.onFailure { Log.w(TAG, "updateTexImage", it) }.isSuccess
            if (!ok) continue
            item.st.getTransformMatrix(matrix)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, item.fbo)
            GLES30.glViewport(0, 0, item.target.width, item.target.height)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, item.oes)
            GLES30.glUniformMatrix4fv(uMatrix, 1, false, matrix, 0)
            GLES30.glEnableVertexAttribArray(aPos)
            GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 0, quad)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glDisableVertexAttribArray(aPos)
        }
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fb[0])
        GLES30.glViewport(vp[0], vp[1], vp[2], vp[3])
        if (blend) GLES30.glEnable(GLES30.GL_BLEND)
        if (depth) GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        if (scissor) GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        if (cull) GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glUseProgram(0)
    }

    fun pause() = items.forEach { runCatching { if (it.player.isPlaying) it.player.pause() } }
    fun resume() = items.forEach { runCatching { if (!it.player.isPlaying) it.player.start() } }

    fun release() {
        for (item in items) {
            runCatching { item.player.release() }
            runCatching { item.surface.release() }
            runCatching { item.st.release() }
            GLES30.glDeleteFramebuffers(1, intArrayOf(item.fbo), 0)
            GLES30.glDeleteTextures(1, intArrayOf(item.oes), 0)
        }
        if (program != 0) GLES30.glDeleteProgram(program)
        program = 0
    }

    private fun buildProgram(): Int {
        // Row 0 of every .tex is the picture's top, hence 1 - y when filling the 2D texture.
        val vs = """
            attribute vec2 aPos;
            uniform mat4 uMatrix;
            varying vec2 vTex;
            void main() {
                gl_Position = vec4(aPos * 2.0 - 1.0, 0.0, 1.0);
                vTex = (uMatrix * vec4(aPos.x, 1.0 - aPos.y, 0.0, 1.0)).xy;
            }
        """.trimIndent()
        val fs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES uTex;
            void main() { gl_FragColor = vec4(texture2D(uTex, vTex).rgb, 1.0); }
        """.trimIndent()
        fun shader(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
            val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) { Log.e(TAG, "shader: " + GLES30.glGetShaderInfoLog(s)); GLES30.glDeleteShader(s); return 0 }
            return s
        }
        val v = shader(GLES30.GL_VERTEX_SHADER, vs)
        val f = shader(GLES30.GL_FRAGMENT_SHADER, fs)
        if (v == 0 || f == 0) return 0
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f); GLES30.glLinkProgram(p)
        GLES30.glDeleteShader(v); GLES30.glDeleteShader(f)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) { Log.e(TAG, "link: " + GLES30.glGetProgramInfoLog(p)); GLES30.glDeleteProgram(p); return 0 }
        aPos = GLES30.glGetAttribLocation(p, "aPos")
        uMatrix = GLES30.glGetUniformLocation(p, "uMatrix")
        return p
    }

    companion object {
        private const val TAG = "VideoTextures"
        private const val OES = GLES11Ext.GL_TEXTURE_EXTERNAL_OES

        /** Starts a player for each of the scene's video textures; null when it has none. */
        fun attach(ctx: Context, scene: NativeScene, status: (String) -> Unit = {}): VideoTextures? {
            val videos = scene.videos()
            if (videos.isEmpty()) return null
            val items = videos.mapIndexedNotNull { i, v ->
                runCatching {
                    val file = File(ctx.cacheDir, "scene_video_$i.mp4").apply { writeBytes(v.data) }
                    val tex = IntArray(1); GLES30.glGenTextures(1, tex, 0)
                    GLES30.glBindTexture(OES, tex[0])
                    GLES30.glTexParameteri(OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                    GLES30.glTexParameteri(OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                    GLES30.glTexParameteri(OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                    GLES30.glTexParameteri(OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                    GLES30.glBindTexture(OES, 0)
                    val fbo = IntArray(1); GLES30.glGenFramebuffers(1, fbo, 0)
                    val saved = IntArray(1); GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, saved, 0)
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
                    GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, v.texture, 0)
                    val complete = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, saved[0])
                    check(complete) { "video target framebuffer incomplete" }
                    val st = SurfaceTexture(tex[0])
                    val surface = Surface(st)
                    val player = MediaPlayer().apply {
                        setDataSource(file.absolutePath)
                        setSurface(surface)
                        isLooping = true
                        setVolume(0f, 0f)
                        prepare()
                        start()
                    }
                    val item = Item(v, tex[0], st, player, surface, fbo[0])
                    st.setOnFrameAvailableListener { item.fresh = true }
                    status("video $i ${v.width}x${v.height} playing")
                    item
                }.onFailure {
                    Log.e(TAG, "video $i failed", it)
                    status("video $i failed: ${it.message}")
                }.getOrNull()
            }
            return if (items.isEmpty()) null else VideoTextures(items)
        }
    }
}
