package com.weworkshop.app.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.weworkshop.app.R
import java.io.File

/** Width / height of this phone's screen in portrait, the shape the wallpaper fills. */
fun screenAspect(ctx: Context): Float {
    val dm = ctx.resources.displayMetrics
    return minOf(dm.widthPixels, dm.heightPixels).toFloat() / maxOf(dm.widthPixels, dm.heightPixels).coerceAtLeast(1)
}

/** A frame from early in [video] for previews, at most about 1024 px on its long side. */
fun loadPreviewFrame(video: File): Bitmap? {
    val mmr = MediaMetadataRetriever()
    return try {
        mmr.setDataSource(video.absolutePath)
        val durationUs = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
        mmr.getScaledFrameAtTime(durationUs / 10, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1024, 1024)
    } catch (_: Exception) {
        null
    } finally {
        mmr.release()
    }
}

/** Where the screen-shaped frame sits in a [size] canvas, leaving [pad] around it. */
private fun frameRect(size: Size, aspect: Float, pad: Float): Rect {
    val h = minOf(size.height - 2 * pad, (size.width - 2 * pad) / aspect).coerceAtLeast(1f)
    val w = h * aspect
    return Rect(Offset((size.width - w) / 2, (size.height - h) / 2), Size(w, h))
}

/**
 * Draws [frame] as the wallpaper shows it inside a screen-shaped frame: the same crop, zoom,
 * position, rotation and flips as the GPU effects, plus saturation and the status bar shade.
 * With [showOutside], the rest of the video is drawn dimmed around the frame, like a photo
 * editor's crop view.
 */
private fun DrawScope.drawCrop(frame: Bitmap, look: WallpaperLook, aspect: Float, rect: Rect, showOutside: Boolean) {
    val t = look.sourceTransform(frame.width.toFloat() / frame.height, aspect)
    val det = t[0] * t[3] - t[1] * t[2]
    if (det == 0f) return
    // Video pixel (u, v) -> video point s -> screen point p = A^-1 (s - o) -> canvas.
    fun map(u: Float, v: Float): Pair<Float, Float> {
        val sx = 2f * u / frame.width - 1f - t[4]
        val sy = 1f - 2f * v / frame.height - t[5]
        val px = (t[3] * sx - t[1] * sy) / det
        val py = (-t[2] * sx + t[0] * sy) / det
        return rect.center.x + px * rect.width / 2 to rect.center.y - py * rect.height / 2
    }
    val w = frame.width.toFloat()
    val h = frame.height.toFloat()
    val (x0, y0) = map(0f, 0f)
    val (x1, y1) = map(w, 0f)
    val (x2, y2) = map(0f, h)
    val m = Matrix().apply { setPolyToPoly(floatArrayOf(0f, 0f, w, 0f, 0f, h), 0, floatArrayOf(x0, y0, x1, y1, x2, y2), 0, 3) }
    val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        if (look.saturation != 100) colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(look.saturation / 100f) })
    }
    fun image() = drawIntoCanvas { it.nativeCanvas.drawBitmap(frame, m, paint) }
    if (showOutside) image() else clipRect(rect.left, rect.top, rect.right, rect.bottom) { image() }

    if (look.shade > 0) {
        val c = if (look.shadeWhite) Color.White else Color.Black
        val a = look.shade / 100f
        drawRect(
            Brush.verticalGradient(
                0f to c.copy(alpha = a), 0.5f to c.copy(alpha = a / 2), 1f to c.copy(alpha = 0f),
                startY = rect.top, endY = rect.top + rect.height * 0.22f,
            ),
            topLeft = rect.topLeft, size = Size(rect.width, rect.height * 0.22f),
        )
    }
    if (showOutside) {
        val outside = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            addRect(rect)
        }
        drawPath(outside, Color.Black.copy(alpha = 0.6f))
        for (i in 1..2) {
            val x = rect.left + rect.width * i / 3
            val y = rect.top + rect.height * i / 3
            drawLine(Color.White.copy(alpha = 0.35f), Offset(x, rect.top), Offset(x, rect.bottom), 1f)
            drawLine(Color.White.copy(alpha = 0.35f), Offset(rect.left, y), Offset(rect.right, y), 1f)
        }
        drawRect(Color.White, rect.topLeft, rect.size, style = Stroke(2.dp.toPx()))
    }
}

/** Small, non-interactive picture of what the wallpaper shows with [look]. */
@Composable
fun CropThumbnail(frame: Bitmap?, look: WallpaperLook, aspect: Float, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (frame == null) {
            CircularProgressIndicator(strokeWidth = 2.dp)
        } else {
            Canvas(Modifier.fillMaxSize()) {
                val rect = frameRect(size, aspect, 0f)
                drawRect(Color.Black, rect.topLeft, rect.size)
                drawCrop(frame, look, aspect, rect, showOutside = false)
            }
        }
    }
}

/**
 * Full-screen crop editor, like a gallery's: drag to move the video, pinch to zoom, and
 * buttons to rotate and flip. Double-tap toggles between filling the screen and 2x.
 */
@Composable
fun CropEditorScreen(frame: Bitmap?, initial: WallpaperLook, aspect: Float, onDone: (WallpaperLook) -> Unit, onCancel: () -> Unit) {
    var look by remember { mutableStateOf(initial) }
    val current by rememberUpdatedState(look)
    BackHandler(onBack = onCancel)
    Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, stringResource(R.string.cancel)) }
                Text(stringResource(R.string.wp_transform), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { onDone(look) }) { Icon(Icons.Filled.Check, stringResource(R.string.wp_crop_done)) }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (frame == null) {
                    CircularProgressIndicator()
                } else {
                    val inAspect = frame.width.toFloat() / frame.height
                    Canvas(
                        Modifier.fillMaxSize()
                            .pointerInput(frame) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    val rect = frameRect(size.let { Size(it.width.toFloat(), it.height.toFloat()) }, aspect, 24.dp.toPx())
                                    look = current.copy(zoom = (current.zoom * zoom).coerceIn(1f, WallpaperLook.MAX_ZOOM))
                                        .dragged(pan.x / rect.width, pan.y / rect.height, inAspect, aspect)
                                }
                            }
                            .pointerInput(frame) {
                                detectTapGestures(onDoubleTap = {
                                    look = current.copy(zoom = if (current.zoom > 1f) 1f else 2f).dragged(0f, 0f, inAspect, aspect)
                                })
                            },
                    ) {
                        drawCrop(frame, look, aspect, frameRect(size, aspect, 24.dp.toPx()), showOutside = true)
                    }
                    if (look.zoom > 1.005f) {
                        Text(
                            "%.1f×".format(look.zoom),
                            Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.wp_crop_hint),
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
            )
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                ToolButton(R.string.wp_rotate_left, { Icon(Icons.AutoMirrored.Filled.RotateLeft, null) }) {
                    look = look.copy(rotation = (look.rotation + 270) % 360).dragged(0f, 0f, frame.aspect(), aspect)
                }
                ToolButton(R.string.wp_rotate_right, { Icon(Icons.AutoMirrored.Filled.RotateRight, null) }) {
                    look = look.copy(rotation = (look.rotation + 90) % 360).dragged(0f, 0f, frame.aspect(), aspect)
                }
                ToolButton(R.string.wp_flip_h, { Icon(Icons.Filled.Flip, null) }, selected = look.flipH) {
                    look = look.copy(flipH = !look.flipH)
                }
                ToolButton(R.string.wp_flip_v, { Icon(Icons.Filled.Flip, null, Modifier.rotate(90f)) }, selected = look.flipV) {
                    look = look.copy(flipV = !look.flipV)
                }
                ToolButton(R.string.wp_crop_reset, { Icon(Icons.Filled.RestartAlt, null) }) {
                    look = look.copy(rotation = 0, flipH = false, flipV = false, zoom = 1f, panX = 0f, panY = 0f)
                }
            }
        }
    }
}

private fun Bitmap?.aspect() = if (this == null || height == 0) 1f else width.toFloat() / height

@Composable
private fun ToolButton(label: Int, icon: @Composable () -> Unit, selected: Boolean = false, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else Color.White
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CompositionLocalProvider(LocalContentColor provides color) {
            IconButton(onClick = onClick) { icon() }
            Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
        }
    }
}
