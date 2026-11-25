package com.weworkshop.app.scene

import android.util.Log

/**
 * JNI wrapper around libwe_scene.so (Rust, apolline-scene: MIT OR Apache-2.0).
 * [create], [draw], [setSpectrum] and [destroy] must run on the GL thread that owns the current EGL context.
 */
class NativeScene private constructor(private var handle: Long) {

    /** One frame into the bound framebuffer; true when the scene wants another frame soon. */
    fun draw(width: Int, height: Int, time: Float, delta: Float, px: Float, py: Float): Boolean =
        handle != 0L && nativeDraw(handle, width, height, time, delta, px, py)

    /** 64 left + 64 right bins, roughly 0..1. */
    fun setSpectrum(bins: FloatArray) {
        if (handle != 0L && bins.size >= 128) nativeSetSpectrum(handle, bins)
    }

    /** Bit 0 pointer, bit 1 time of day, bit 2 audio. */
    val needs: Int get() = if (handle == 0L) 0 else nativeNeeds(handle)
    val wantsAudio: Boolean get() = needs and 4 != 0

    fun destroy() {
        val h = handle
        handle = 0
        if (h != 0L) nativeDestroy(h)
    }

    companion object {
        private const val TAG = "NativeScene"

        private val loaded: Boolean by lazy {
            runCatching { System.loadLibrary("we_scene") }
                .onFailure { Log.e(TAG, "libwe_scene.so missing for this ABI", it) }
                .isSuccess
        }

        val available: Boolean get() = loaded

        /** What lenient mode dropped while loading the last scene. */
        fun diag(): String = if (loaded) nativeLastError() else ""

        /** Builds a renderer on the current GL context, or throws with the native reason. */
        fun create(projectDir: String, assetsDir: String?, propertiesJson: String? = null): NativeScene {
            check(loaded) { "native scene library not available" }
            val h = nativeCreate(projectDir, assetsDir, propertiesJson)
            if (h == 0L) error(nativeLastError().ifBlank { "unknown error" })
            return NativeScene(h)
        }

        /** What the scene uses that isn't drawn yet (one per line), or "ERROR: ..." when it can't be opened. */
        fun check(projectDir: String, assetsDir: String?): List<String> {
            if (!loaded) return listOf("ERROR: native scene library not available")
            return nativeCheck(projectDir, assetsDir).lines().filter { it.isNotBlank() }
        }

        @JvmStatic private external fun nativeCreate(project: String, assets: String?, properties: String?): Long
        @JvmStatic private external fun nativeDraw(h: Long, w: Int, hgt: Int, time: Float, delta: Float, px: Float, py: Float): Boolean
        @JvmStatic private external fun nativeSetSpectrum(h: Long, bins: FloatArray)
        @JvmStatic private external fun nativeNeeds(h: Long): Int
        @JvmStatic private external fun nativeDestroy(h: Long)
        @JvmStatic private external fun nativeLastError(): String
        @JvmStatic private external fun nativeCheck(project: String, assets: String?): String
    }
}
