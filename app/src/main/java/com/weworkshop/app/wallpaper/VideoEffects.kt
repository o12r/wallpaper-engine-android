package com.weworkshop.app.wallpaper

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.Effect
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GaussianBlur
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Look of the video wallpaper: a shade behind the status bar, blur, saturation, sharp upscaling,
 * and the crop (zoom, position, rotation and flips). Shade and blur are 0..100, saturation
 * 0..200 (100 = unchanged).
 */
data class WallpaperLook(
    val shade: Int = 0,
    /** Shade toward white (for dark status bar text) instead of black. */
    val shadeWhite: Boolean = false,
    val blur: Int = 0,
    val saturation: Int = 100,
    val upscale: Boolean = false,
    /** Clockwise, in degrees: 0, 90, 180 or 270. */
    val rotation: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    /** 1 fills the screen; up to [MAX_ZOOM]. */
    val zoom: Float = 1f,
    /** Which part of the video shows, -1..1 per axis (0 = centered, 1 = right / bottom edge). */
    val panX: Float = 0f,
    val panY: Float = 0f,
) {
    val hasGeometry get() = rotation != 0 || flipH || flipV || zoom != 1f || panX != 0f || panY != 0f
    val isDefault get() = shade == 0 && blur == 0 && saturation == 100 && !upscale && !hasGeometry
    val geometryKey get() = "$rotation,$flipH,$flipV,$zoom,$panX,$panY"

    /**
     * Maps an output point (x, y in -1..1, y up) to the video point it shows, as
     * source = M * output + offset: crop-to-fill for the rotated video's [inAspect] on an
     * [outAspect] screen, then zoom, pan, flip and rotation. Returns (m00, m01, m10, m11, ox, oy).
     */
    fun sourceTransform(inAspect: Float, outAspect: Float): FloatArray {
        val (kx, ky) = scales(inAspect, outAspect)
        // Pan within what the crop leaves over; y is flipped (positive panY = lower part).
        val bx = panX * (1f - kx)
        val by = -panY * (1f - ky)
        val fx = if (flipH) -1f else 1f
        val fy = if (flipV) -1f else 1f
        // Display point q -> video point s for an image turned clockwise by [rotation].
        val (r00, r01, r10, r11) = when (rotation) {
            90 -> listOf(0f, -1f, 1f, 0f)
            180 -> listOf(-1f, 0f, 0f, -1f)
            270 -> listOf(0f, 1f, -1f, 0f)
            else -> listOf(1f, 0f, 0f, 1f)
        }
        // Window point d = K * p + b in the turned and flipped video; s = R * F * d.
        val a00 = r00 * fx * kx; val a01 = r01 * fy * ky
        val a10 = r10 * fx * kx; val a11 = r11 * fy * ky
        val ox = r00 * fx * bx + r01 * fy * by
        val oy = r10 * fx * bx + r11 * fy * by
        return floatArrayOf(a00, a01, a10, a11, ox, oy)
    }

    /**
     * Share of the turned video's width and height (both -1..1) that the screen shows: the
     * crop-to-fill for [inAspect] video on an [outAspect] screen, divided by the zoom.
     */
    fun scales(inAspect: Float, outAspect: Float): Pair<Float, Float> {
        val turned = rotation == 90 || rotation == 270
        val rotAspect = if (turned) 1f / inAspect else inAspect
        val z = zoom.coerceIn(1f, MAX_ZOOM)
        val kx = (if (rotAspect > outAspect) outAspect / rotAspect else 1f) / z
        val ky = (if (rotAspect > outAspect) 1f else rotAspect / outAspect) / z
        return kx to ky
    }

    /**
     * Moved so the video follows a finger that went ([dx], [dy]) as a share of the screen's
     * width and height (dy down), clamped so the video still covers the screen.
     */
    fun dragged(dx: Float, dy: Float, inAspect: Float, outAspect: Float): WallpaperLook {
        val (kx, ky) = scales(inAspect, outAspect)
        // Window center b = pan * slack moves against the finger by k * (2 * d).
        fun move(pan: Float, k: Float, d: Float): Float {
            val slack = 1f - k
            return if (slack < 1e-4f) 0f else (pan - 2f * k * d / slack).coerceIn(-1f, 1f)
        }
        return copy(panX = move(panX, kx, dx), panY = move(panY, ky, dy))
    }

    /** Parts that need a new effect chain when they change (the rest is read every frame). */
    fun structure(width: Int, height: Int) =
        listOf(blur, upscale && blur == 0, shade > 0 || saturation != 100, width, height)

    companion object {
        const val MAX_ZOOM = 4f
    }
}

/**
 * Builds the GPU effect chain that turns video frames into [width]x[height] wallpaper frames,
 * cropped to fill like the plain player does. [look] is read on every frame, so shade and
 * saturation changes show up without rebuilding the chain.
 */
@UnstableApi
object VideoEffects {
    /** Share of the screen height the shade covers, fading out toward the bottom. */
    private const val SHADE_HEIGHT = 0.22f

    /** Strongest blur, as a Gaussian sigma in full-screen pixels. */
    private const val MAX_BLUR_SIGMA = 36f

    /** Blur runs at this fraction of the screen size; it can't show the lost detail anyway. */
    private const val BLUR_SCALE = 4

    fun build(look: () -> WallpaperLook, width: Int, height: Int): List<Effect> {
        val l = look()
        val out = mutableListOf<Effect>()
        if (l.blur > 0) {
            val w = (width / BLUR_SCALE).coerceAtLeast(1)
            val h = (height / BLUR_SCALE).coerceAtLeast(1)
            out += FillEffect(look, w, h, bicubic = false)
            out += GaussianBlur(MAX_BLUR_SIGMA / BLUR_SCALE * l.blur / 100f)
            out += FillEffect(null, width, height, bicubic = false)
        } else if (l.upscale) {
            out += FillEffect(look, width, height, bicubic = true)
            out += SharpenEffect()
        } else {
            out += FillEffect(look, width, height, bicubic = false)
        }
        if (l.shade > 0 || l.saturation != 100) out += LookEffect(look)
        return out
    }

    private val IDENTITY = floatArrayOf(1f, 0f, 0f, 1f)
    private val ZERO = floatArrayOf(0f, 0f)

    private const val VERTEX = """
        attribute vec4 aFramePosition;
        uniform vec4 uMat;
        uniform vec2 uOffset;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aFramePosition;
            vec2 p = aFramePosition.xy;
            vec2 s = vec2(uMat.x * p.x + uMat.y * p.y, uMat.z * p.x + uMat.w * p.y) + uOffset;
            vTexCoord = 0.5 + 0.5 * s;
        }
    """

    /**
     * Scales the frame to the output size, cropped to fill, with [look]'s rotation, flip, zoom
     * and pan (read every frame). Without [look] it just center-crops.
     */
    private class FillEffect(val look: (() -> WallpaperLook)?, val width: Int, val height: Int, val bicubic: Boolean) : GlEffect {
        override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
            Program(if (bicubic) BICUBIC else BILINEAR) { p, inW, inH ->
                val t = (look?.invoke() ?: WallpaperLook()).sourceTransform(inW.toFloat() / inH, width.toFloat() / height)
                p.setFloatsUniform("uMat", floatArrayOf(t[0], t[1], t[2], t[3]))
                p.setFloatsUniform("uOffset", floatArrayOf(t[4], t[5]))
                if (bicubic) p.setFloatsUniform("uTexSize", floatArrayOf(inW.toFloat(), inH.toFloat()))
            }.sized { _, _ -> Size(width, height) }

        companion object {
            const val BILINEAR = """
                precision mediump float;
                uniform sampler2D uTex;
                varying vec2 vTexCoord;
                void main() { gl_FragColor = texture2D(uTex, vTexCoord); }
            """

            /** Catmull-Rom bicubic in 9 bilinear taps (sharper than bilinear when enlarging). */
            const val BICUBIC = """
                precision highp float;
                uniform sampler2D uTex;
                uniform vec2 uTexSize;
                varying vec2 vTexCoord;
                void main() {
                    vec2 pos = vTexCoord * uTexSize;
                    vec2 p1 = floor(pos - 0.5) + 0.5;
                    vec2 f = pos - p1;
                    vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
                    vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
                    vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
                    vec2 w3 = f * f * (-0.5 + 0.5 * f);
                    vec2 w12 = w1 + w2;
                    vec2 t0 = (p1 - 1.0) / uTexSize;
                    vec2 t3 = (p1 + 2.0) / uTexSize;
                    vec2 t12 = (p1 + w2 / w12) / uTexSize;
                    vec4 c = texture2D(uTex, vec2(t0.x, t0.y)) * w0.x * w0.y
                        + texture2D(uTex, vec2(t12.x, t0.y)) * w12.x * w0.y
                        + texture2D(uTex, vec2(t3.x, t0.y)) * w3.x * w0.y
                        + texture2D(uTex, vec2(t0.x, t12.y)) * w0.x * w12.y
                        + texture2D(uTex, vec2(t12.x, t12.y)) * w12.x * w12.y
                        + texture2D(uTex, vec2(t3.x, t12.y)) * w3.x * w12.y
                        + texture2D(uTex, vec2(t0.x, t3.y)) * w0.x * w3.y
                        + texture2D(uTex, vec2(t12.x, t3.y)) * w12.x * w3.y
                        + texture2D(uTex, vec2(t3.x, t3.y)) * w3.x * w3.y;
                    gl_FragColor = clamp(c, 0.0, 1.0);
                }
            """
        }
    }

    /** Contrast-adaptive sharpening (after AMD FidelityFX CAS): restores edges lost to scaling. */
    private class SharpenEffect : GlEffect {
        override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
            Program(SHADER) { p, inW, inH ->
                p.setFloatsUniform("uMat", IDENTITY)
                p.setFloatsUniform("uOffset", ZERO)
                p.setFloatsUniform("uTexel", floatArrayOf(1f / inW, 1f / inH))
            }

        companion object {
            const val SHADER = """
                precision mediump float;
                uniform sampler2D uTex;
                uniform vec2 uTexel;
                varying vec2 vTexCoord;
                void main() {
                    vec3 b = texture2D(uTex, vTexCoord + vec2(0.0, -uTexel.y)).rgb;
                    vec3 d = texture2D(uTex, vTexCoord + vec2(-uTexel.x, 0.0)).rgb;
                    vec4 e4 = texture2D(uTex, vTexCoord);
                    vec3 e = e4.rgb;
                    vec3 f = texture2D(uTex, vTexCoord + vec2(uTexel.x, 0.0)).rgb;
                    vec3 h = texture2D(uTex, vTexCoord + vec2(0.0, uTexel.y)).rgb;
                    vec3 mn = min(min(min(d, e), min(f, b)), h);
                    vec3 mx = max(max(max(d, e), max(f, b)), h);
                    vec3 amp = sqrt(clamp(min(mn, 2.0 - mx) / max(mx, vec3(0.0001)), 0.0, 1.0));
                    vec3 w = amp * (-1.0 / 6.5);
                    vec3 c = (b * w + d * w + f * w + h * w + e) / (1.0 + 4.0 * w);
                    gl_FragColor = vec4(clamp(c, 0.0, 1.0), e4.a);
                }
            """
        }
    }

    /** Saturation and the shade behind the status bar, read from [look] on every frame. */
    private class LookEffect(val look: () -> WallpaperLook) : GlEffect {
        override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
            Program(SHADER) { p, _, _ ->
                val l = look()
                p.setFloatsUniform("uMat", IDENTITY)
                p.setFloatsUniform("uOffset", ZERO)
                p.setFloatUniform("uSaturation", l.saturation / 100f)
                p.setFloatUniform("uShade", l.shade / 100f)
                p.setFloatUniform("uShadeTo", if (l.shadeWhite) 1f else 0f)
                p.setFloatUniform("uShadeHeight", SHADE_HEIGHT)
            }

        companion object {
            const val SHADER = """
                precision mediump float;
                uniform sampler2D uTex;
                uniform float uSaturation;
                uniform float uShade;
                uniform float uShadeTo;
                uniform float uShadeHeight;
                varying vec2 vTexCoord;
                void main() {
                    vec4 c = texture2D(uTex, vTexCoord);
                    float l = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722));
                    vec3 rgb = clamp(mix(vec3(l), c.rgb, uSaturation), 0.0, 1.0);
                    // Texture y runs bottom to top, so the top of the screen is y = 1.
                    float t = clamp((1.0 - vTexCoord.y) / uShadeHeight, 0.0, 1.0);
                    float a = uShade * (1.0 - t * t * (3.0 - 2.0 * t));
                    gl_FragColor = vec4(mix(rgb, vec3(uShadeTo), a), c.a);
                }
            """
        }
    }

    /**
     * A one-pass shader: [VERTEX] with [fragment], uniforms set by [uniforms] before each draw.
     * Output size is the input's unless [sized] says otherwise.
     */
    private class Program(
        private val fragment: String,
        private val uniforms: (GlProgram, Int, Int) -> Unit,
    ) : BaseGlShaderProgram(false, 1) {
        private var outputSize: (Int, Int) -> Size = { w, h -> Size(w, h) }
        private var program: GlProgram? = null
        private var inW = 1
        private var inH = 1

        fun sized(size: (Int, Int) -> Size) = apply { outputSize = size }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            inW = inputWidth
            inH = inputHeight
            return outputSize(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                val p = program ?: GlProgram(VERTEX, fragment).also {
                    it.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
                    program = it
                }
                p.use()
                p.setSamplerTexIdUniform("uTex", inputTexId, 0)
                uniforms(p, inW, inH)
                p.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
            } catch (e: Exception) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            try { program?.delete() } catch (e: Exception) { throw VideoFrameProcessingException(e) }
            program = null
        }
    }
}
