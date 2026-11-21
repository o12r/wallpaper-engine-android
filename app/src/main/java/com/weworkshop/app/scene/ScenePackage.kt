package com.weworkshop.app.scene

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Read access to a Wallpaper Engine scene: either a packed `scene.pkg` or a folder that already
 * holds the unpacked files (scene.json, models/, materials/ ...). Format follows RePKG.
 */
class ScenePackage private constructor(
    private val raf: RandomAccessFile?,
    private val dataStart: Long,
    private val entries: Map<String, Pair<Long, Int>>,
    private val dir: File?,
) : Closeable {

    fun has(name: String): Boolean = if (dir != null) File(dir, name).isFile else name in entries

    fun read(name: String): ByteArray? {
        if (dir != null) return File(dir, name).takeIf { it.isFile }?.readBytes()
        val (off, len) = entries[name] ?: return null
        val out = ByteArray(len)
        synchronized(this) {
            raf!!.seek(dataStart + off)
            raf.readFully(out)
        }
        return out
    }

    fun readText(name: String): String? = read(name)?.toString(Charsets.UTF_8)

    override fun close() {
        raf?.close()
    }

    companion object {
        /** Opens the scene in [contentDir], or returns null when it holds no scene. */
        fun open(contentDir: File): ScenePackage? {
            val pkg = File(contentDir, "scene.pkg")
            if (pkg.isFile) return openPkg(pkg)
            if (File(contentDir, "scene.json").isFile) return ScenePackage(null, 0, emptyMap(), contentDir)
            return null
        }

        private fun openPkg(file: File): ScenePackage {
            val raf = RandomAccessFile(file, "r")
            try {
                // Header: magic, entry count, then (path, offset, length) per entry; little endian.
                val head = ByteArray(minOf(file.length(), 4L * 1024 * 1024).toInt())
                raf.readFully(head)
                val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
                fun str(max: Int): String {
                    val n = b.int
                    if (n !in 0..max) throw IOException("Not a scene package (bad string length $n)")
                    val s = String(head, b.position(), n, Charsets.UTF_8)
                    b.position(b.position() + n)
                    return s
                }
                val magic = str(32)
                if (!magic.startsWith("PKGV")) {
                    throw IOException(
                        if (head.all { it.toInt() == 0 }) "scene.pkg is empty (incomplete download, re-download it)"
                        else "Not a scene package ($magic)",
                    )
                }
                val count = b.int
                if (count !in 0..100_000) throw IOException("Bad entry count $count")
                val entries = HashMap<String, Pair<Long, Int>>(count)
                repeat(count) {
                    val name = str(1024)
                    val off = b.int.toLong() and 0xFFFFFFFFL
                    val len = b.int
                    entries[name] = off to len
                }
                return ScenePackage(raf, b.position().toLong(), entries, null)
            } catch (t: Throwable) {
                raf.close()
                throw t
            }
        }
    }
}

/** A decoded `.tex`: a still bitmap, or the bytes of an embedded MP4 video texture. */
sealed interface TexImage {
    class Still(val bitmap: Bitmap) : TexImage
    class Video(val mp4: ByteArray, val width: Int, val height: Int) : TexImage
}

/** Decoder for Wallpaper Engine `.tex` textures (TEXV0005 / TEXI0001 / TEXB000x). */
object TexDecoder {
    private const val FMT_RGBA8888 = 0
    private const val FMT_DXT5 = 4
    private const val FMT_DXT3 = 6
    private const val FMT_DXT1 = 7
    private const val FMT_RG88 = 8
    private const val FMT_R8 = 9
    private const val FIF_UNKNOWN = -1
    private const val FIF_MP4 = 35

    /**
     * Decodes the first image of a texture. [maxSide] downsamples large textures so a scene
     * with several 4K layers fits in memory.
     */
    fun decode(data: ByteArray, maxSide: Int): TexImage {
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        fun nstr(): String {
            val start = b.position()
            while (b.hasRemaining() && b.get().toInt() != 0) { /* scan to NUL */ }
            return String(data, start, b.position() - start - 1, Charsets.US_ASCII)
        }
        val m1 = nstr()
        val m2 = nstr()
        if (m1 != "TEXV0005" || m2 != "TEXI0001") throw IOException("Unknown texture ($m1/$m2)")
        val format = b.int
        b.int // flags
        b.int; b.int // texture (power of two) size
        val imgW = b.int
        val imgH = b.int
        b.int // unknown

        val container = nstr()
        val imageCount = b.int
        if (imageCount < 1) throw IOException("Texture has no images")
        var fif = FIF_UNKNOWN
        var version = container.removePrefix("TEXB").toIntOrNull() ?: throw IOException("Unknown container $container")
        when (version) {
            1, 2 -> {}
            3 -> fif = b.int
            4 -> {
                fif = b.int
                val isMp4 = b.int == 1
                if (fif == FIF_UNKNOWN && isMp4) fif = FIF_MP4
                if (fif != FIF_MP4) version = 3
            }
            else -> throw IOException("Unknown container $container")
        }

        // First image, first (largest) mipmap.
        val mipCount = b.int
        if (mipCount < 1) throw IOException("Texture has no mipmaps")
        if (version == 4) {
            b.int; b.int // params, always 1, 2
            nstr() // condition json
            b.int // param, always 1
        }
        val w = b.int
        val h = b.int
        var lz4 = false
        var rawLen = 0
        if (version >= 2) {
            lz4 = b.int == 1
            rawLen = b.int
        }
        val len = b.int
        if (len < 0 || len > b.remaining()) throw IOException("Corrupt texture (mipmap size $len)")
        var bytes = ByteArray(len).also { b.get(it) }
        if (lz4) bytes = Lz4.decompressBlock(bytes, rawLen)

        if (fif == FIF_MP4) return TexImage.Video(bytes, imgW, imgH)
        if (fif != FIF_UNKNOWN) {
            // An embedded image file (PNG/JPEG/...).
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
            }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?: throw IOException("Undecodable embedded image")
            return TexImage.Still(bmp)
        }

        val pixels = when (format) {
            FMT_RGBA8888 -> rgba(bytes, w, h)
            FMT_DXT1 -> Dxt.decode(bytes, w, h, Dxt.Kind.DXT1)
            FMT_DXT3 -> Dxt.decode(bytes, w, h, Dxt.Kind.DXT3)
            FMT_DXT5 -> Dxt.decode(bytes, w, h, Dxt.Kind.DXT5)
            FMT_RG88 -> IntArray(w * h) { i ->
                val l = bytes[i * 2].toInt() and 0xFF
                val a = bytes[i * 2 + 1].toInt() and 0xFF
                (a shl 24) or (l shl 16) or (l shl 8) or l
            }
            FMT_R8 -> IntArray(w * h) { i ->
                val l = bytes[i].toInt() and 0xFF
                (0xFF shl 24) or (l shl 16) or (l shl 8) or l
            }
            else -> throw IOException("Unsupported texture format $format")
        }
        // The stored texture may be padded to a power of two; the picture is the top-left imgW x imgH.
        val cw = imgW.coerceIn(1, w)
        val ch = imgH.coerceIn(1, h)
        var bmp = Bitmap.createBitmap(pixels, 0, w, cw, ch, Bitmap.Config.ARGB_8888)
        val s = sampleSize(cw, ch, maxSide)
        if (s > 1) {
            val scaled = Bitmap.createScaledBitmap(bmp, cw / s, ch / s, true)
            bmp.recycle()
            bmp = scaled
        }
        return TexImage.Still(bmp)
    }

    private fun sampleSize(w: Int, h: Int, maxSide: Int): Int {
        var s = 1
        while (maxOf(w, h) / (s * 2) >= maxSide) s *= 2
        return s
    }

    private fun rgba(bytes: ByteArray, w: Int, h: Int) = IntArray(w * h) { i ->
        val o = i * 4
        ((bytes[o + 3].toInt() and 0xFF) shl 24) or ((bytes[o].toInt() and 0xFF) shl 16) or
            ((bytes[o + 1].toInt() and 0xFF) shl 8) or (bytes[o + 2].toInt() and 0xFF)
    }
}

/** LZ4 block format decompressor (no frame header), as used by `.tex` mipmaps. */
object Lz4 {
    fun decompressBlock(src: ByteArray, dstLen: Int): ByteArray {
        val dst = ByteArray(dstLen)
        var s = 0
        var d = 0
        while (s < src.size) {
            val token = src[s++].toInt() and 0xFF
            var lit = token ushr 4
            if (lit == 15) {
                var x: Int
                do { x = src[s++].toInt() and 0xFF; lit += x } while (x == 255)
            }
            System.arraycopy(src, s, dst, d, lit)
            s += lit
            d += lit
            if (s >= src.size) break // the last sequence has literals only
            val off = (src[s].toInt() and 0xFF) or ((src[s + 1].toInt() and 0xFF) shl 8)
            s += 2
            var ml = token and 15
            if (ml == 15) {
                var x: Int
                do { x = src[s++].toInt() and 0xFF; ml += x } while (x == 255)
            }
            ml += 4
            var m = d - off
            if (m < 0) throw IOException("Corrupt LZ4 data")
            repeat(ml) { dst[d++] = dst[m++] } // byte by byte: matches may overlap
        }
        return dst
    }
}

/** S3TC (DXT1/3/5) block decoder to ARGB pixels. */
object Dxt {
    enum class Kind { DXT1, DXT3, DXT5 }

    fun decode(src: ByteArray, w: Int, h: Int, kind: Kind): IntArray {
        val out = IntArray(w * h)
        val blockBytes = if (kind == Kind.DXT1) 8 else 16
        val bw = (w + 3) / 4
        val bh = (h + 3) / 4
        val colors = IntArray(4)
        val alphas = IntArray(16)
        for (by in 0 until bh) for (bx in 0 until bw) {
            var o = (by * bw + bx) * blockBytes
            if (o + blockBytes > src.size) continue
            when (kind) {
                Kind.DXT1 -> alphas.fill(255)
                Kind.DXT3 -> {
                    for (i in 0 until 16) {
                        val v = (src[o + i / 2].toInt() ushr ((i and 1) * 4)) and 0xF
                        alphas[i] = v * 17
                    }
                    o += 8
                }
                Kind.DXT5 -> {
                    val a0 = src[o].toInt() and 0xFF
                    val a1 = src[o + 1].toInt() and 0xFF
                    var bits = 0L
                    for (i in 0 until 6) bits = bits or ((src[o + 2 + i].toLong() and 0xFF) shl (8 * i))
                    for (i in 0 until 16) {
                        val idx = ((bits ushr (3 * i)) and 7).toInt()
                        alphas[i] = when {
                            idx == 0 -> a0
                            idx == 1 -> a1
                            a0 > a1 -> ((8 - idx) * a0 + (idx - 1) * a1) / 7
                            idx == 6 -> 0
                            idx == 7 -> 255
                            else -> ((6 - idx) * a0 + (idx - 1) * a1) / 5
                        }
                    }
                    o += 8
                }
            }
            val c0 = (src[o].toInt() and 0xFF) or ((src[o + 1].toInt() and 0xFF) shl 8)
            val c1 = (src[o + 2].toInt() and 0xFF) or ((src[o + 3].toInt() and 0xFF) shl 8)
            colors[0] = rgb565(c0)
            colors[1] = rgb565(c1)
            if (kind != Kind.DXT1 || c0 > c1) {
                colors[2] = mix(colors[0], colors[1], 2, 1, 3)
                colors[3] = mix(colors[0], colors[1], 1, 2, 3)
            } else {
                colors[2] = mix(colors[0], colors[1], 1, 1, 2)
                colors[3] = 0 // transparent black
            }
            val idx = (src[o + 4].toInt() and 0xFF) or ((src[o + 5].toInt() and 0xFF) shl 8) or
                ((src[o + 6].toInt() and 0xFF) shl 16) or ((src[o + 7].toInt() and 0xFF) shl 24)
            for (i in 0 until 16) {
                val x = bx * 4 + (i and 3)
                val y = by * 4 + (i shr 2)
                if (x >= w || y >= h) continue
                val ci = (idx ushr (2 * i)) and 3
                val c = colors[ci]
                val a = if (kind == Kind.DXT1) (if (ci == 3 && c0 <= c1) 0 else 255) else alphas[i]
                out[y * w + x] = (a shl 24) or (c and 0xFFFFFF)
            }
        }
        return out
    }

    private fun rgb565(c: Int): Int {
        val r = (c ushr 11) and 31
        val g = (c ushr 5) and 63
        val b = c and 31
        return ((r * 255 / 31) shl 16) or ((g * 255 / 63) shl 8) or (b * 255 / 31)
    }

    private fun mix(a: Int, b: Int, wa: Int, wb: Int, div: Int): Int {
        fun ch(shift: Int) = ((((a ushr shift) and 0xFF) * wa + ((b ushr shift) and 0xFF) * wb) / div) shl shift
        return ch(16) or ch(8) or ch(0)
    }
}
