package com.weworkshop.app.wallpaper

import android.app.WallpaperColors
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.util.Log

/**
 * Turns what a live wallpaper shows into [WallpaperColors], so launchers and the system UI can pick
 * readable icon, label and status bar colours. The maths mirrors VideoWallpaperService (which does
 * the same for video files); it lives here so the web and scene engines can share it.
 */
object ColorReport {
    private const val TAG = "ColorReport"

    /**
     * Dark text is requested when the strip behind the status bar averages at least this relative
     * luminance. [WallpaperColors.fromBitmap] only asks for dark text on almost entirely bright
     * images, which leaves white text on many light wallpapers.
     */
    const val DARK_TEXT_LUMINANCE = 0.4

    /** Share of the screen height behind the status bar. */
    private const val STATUS_BAR_SHARE = 0.06

    /** Primary colors at least / at most this luminance count as light / dark. */
    private const val LIGHT_PRIMARY = 0.6f
    private const val DARK_PRIMARY = 0.15f

    /** Overall colors of [sample] plus the mean relative luminance of its top strip. */
    fun analyze(sample: Bitmap): Pair<WallpaperColors?, Double> {
        val top = topLuminance(sample)
        if (isBlank(sample)) return null to top
        return WallpaperColors.fromBitmap(sample) to top
    }

    /** Mean relative luminance (0..1) of the strip of [b] that sits behind the status bar. */
    fun topLuminance(b: Bitmap): Double {
        val h = (b.height * STATUS_BAR_SHARE).toInt().coerceIn(1, b.height)
        val px = IntArray(b.width * h)
        b.getPixels(px, 0, b.width, 0, 0, b.width, h)
        fun lin(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return px.sumOf { 0.2126 * lin(Color.red(it)) + 0.7152 * lin(Color.green(it)) + 0.0722 * lin(Color.blue(it)) } / px.size
    }

    /** Whether status bar text should be dark for a wallpaper whose top strip averages [topLuminance]. */
    fun wantsDarkText(topLuminance: Double): Boolean = topLuminance >= DARK_TEXT_LUMINANCE

    /**
     * [base] with the "supports dark text" hint set as asked. The constructor taking hints is public
     * from Android 12; before that, colors of a plain white or black image carry the same hint.
     */
    fun withTextHint(base: WallpaperColors?, darkText: Boolean): WallpaperColors {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Some launchers (e.g. RedMagic/nubia) ignore the hint and pick the status bar colour
            // from how light the primary colour is, so make the primary agree with it.
            val palette = listOfNotNull(base?.primaryColor, base?.secondaryColor, base?.tertiaryColor)
            val primary = if (darkText) {
                palette.firstOrNull { it.luminance() >= LIGHT_PRIMARY } ?: blend(palette.firstOrNull(), Color.WHITE, LIGHT_PRIMARY)
            } else {
                palette.firstOrNull { it.luminance() <= DARK_PRIMARY } ?: blend(palette.firstOrNull(), Color.BLACK, DARK_PRIMARY)
            }
            val rest = palette.filter { it != primary }
            var hints = base?.colorHints ?: 0
            hints = if (darkText) {
                (hints or WallpaperColors.HINT_SUPPORTS_DARK_TEXT) and WallpaperColors.HINT_SUPPORTS_DARK_THEME.inv()
            } else {
                (hints or WallpaperColors.HINT_SUPPORTS_DARK_THEME) and WallpaperColors.HINT_SUPPORTS_DARK_TEXT.inv()
            }
            return WallpaperColors(primary, rest.getOrNull(0), rest.getOrNull(1), hints)
        }
        val solid = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        solid.eraseColor(if (darkText) Color.WHITE else Color.BLACK)
        return WallpaperColors.fromBitmap(solid)
    }

    /** Convenience: colors for a [sample] with the text hint already decided by its top strip. */
    fun colorsFor(sample: Bitmap, label: String): WallpaperColors? {
        val (base, top) = analyze(sample)
        if (base == null) return null
        val dark = wantsDarkText(top)
        Log.i(TAG, "$label: top luminance %.2f darkText=$dark".format(top))
        return withTextHint(base, dark)
    }

    /** True when every sampled pixel is transparent or one flat opaque colour (nothing was drawn). */
    private fun isBlank(b: Bitmap): Boolean {
        val stepX = (b.width / 32).coerceAtLeast(1)
        val stepY = (b.height / 32).coerceAtLeast(1)
        var first = 0
        var n = 0
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                val c = b.getPixel(x, y)
                if (n == 0) first = c else if (c != first) return false
                n++
                x += stepX
            }
            y += stepY
        }
        return n == 0 || Color.alpha(first) == 0
    }

    /** Moves [from] toward [target] (white or black) until its luminance reaches [lum]. */
    private fun blend(from: Color?, target: Int, lum: Float): Color {
        val c = from ?: return Color.valueOf(target)
        val t = Color.valueOf(target)
        var mixed = c
        var f = 0f
        while (f < 1f) {
            f += 0.05f
            mixed = Color.valueOf(
                c.red() + (t.red() - c.red()) * f,
                c.green() + (t.green() - c.green()) * f,
                c.blue() + (t.blue() - c.blue()) * f,
            )
            if (mixed.luminance() >= lum) break
        }
        return mixed
    }

    /** [src] scaled down to at most [maxWidth] pixels wide, to keep the analysis cheap. */
    fun scaled(src: Bitmap, maxWidth: Int = 256): Bitmap {
        if (src.width <= maxWidth) return src
        return Bitmap.createScaledBitmap(src, maxWidth, (src.height.toLong() * maxWidth / src.width).toInt().coerceAtLeast(1), true)
    }
}
