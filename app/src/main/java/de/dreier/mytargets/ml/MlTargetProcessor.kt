package de.dreier.mytargets.ml

import android.content.Context
import android.graphics.*
import android.graphics.BitmapFactory
import android.graphics.PointF
import java.io.File
import java.util.*

data class MlPipelineResult(
    val processedBitmap: Bitmap,
    // normalized positions in range 0..1 relative to processedBitmap (x,y pairs)
    val arrows: List<PointF>
)

class MlTargetProcessor(private val context: Context) {

    fun runPipeline(imageFile: File): MlPipelineResult? {
        // Load source image
        val src = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return null

        // 2x center zoom: crop center half-size, then scale back to original size
        val cropW = (src.width / 2).coerceAtLeast(1)
        val cropH = (src.height / 2).coerceAtLeast(1)
        val left = ((src.width - cropW) / 2)
        val top = ((src.height - cropH) / 2)
        val cropped = Bitmap.createBitmap(src, left, top, cropW, cropH)
        //val processed = Bitmap.createScaledBitmap(cropped, src.width, src.height, true)

        // Produce 3 random arrows (normalized coordinates 0..1)
        val rnd = Random()
        val arrowsNorm = mutableListOf<PointF>()
        for (i in 0 until 3) {
            // to make results visually plausible, bias positions slightly toward center
            val nx = 0.25f + rnd.nextFloat() * 0.5f
            val ny = 0.25f + rnd.nextFloat() * 0.5f
            arrowsNorm.add(PointF(nx, ny))
        }

        // Convert normalized arrows to pixel coordinates for drawing
        val arrowPixels = arrowsNorm.map { p -> PointF(p.x * cropped.width, p.y * cropped.height) }

        // Draw annotations (center + arrows)
        val center = PointF(cropped.width / 2f, cropped.height / 2f)
        val geometry = Geometry(center, listOf(
            PointF(0f, 0f),
            PointF(cropped.width - 1f, 0f),
            PointF(cropped.width - 1f, cropped.height - 1f),
            PointF(0f, cropped.height - 1f)
        ))
        val annotated = drawAnnotations(cropped, geometry, arrowPixels)

        return MlPipelineResult(annotated, arrowsNorm)
    }

    // --- Placeholder data structures & methods ---

    data class Detection(val rect: Rect, val prob: Float) {
        val center: PointF get() = PointF(rect.exactCenterX(), rect.exactCenterY())
    }

    private fun PointF.distance(other: PointF): Float {
        val dx = x - other.x
        val dy = y - other.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun detectTargets(bmp: Bitmap, interpreter: Any?): List<Detection> {
        // Placeholder for future model inference
        return listOf(Detection(Rect(0, 0, bmp.width, bmp.height), 0.9f))
    }

    data class Geometry(val center: PointF, val corners: List<PointF>)
    private fun detectGeometry(bmp: Bitmap, interpreter: Any?): Geometry {
        val center = PointF(bmp.width / 2f, bmp.height / 2f)
        val corners = listOf(
            PointF(0f, 0f),
            PointF(bmp.width - 1f, 0f),
            PointF(bmp.width - 1f, bmp.height - 1f),
            PointF(0f, bmp.height - 1f),
        )
        return Geometry(center, corners)
    }

    private fun detectArrows(bmp: Bitmap, interpreter: Any?): List<PointF> {
        // Placeholder for future model inference
        return listOf(
            PointF(bmp.width * 0.52f, bmp.height * 0.48f),
            PointF(bmp.width * 0.60f, bmp.height * 0.30f),
            PointF(bmp.width * 0.40f, bmp.height * 0.70f),
        )
    }

    private fun cropWithPadding(bmp: Bitmap, rect: Rect, padFraction: Float): Bitmap {
        val padX = (rect.width() * padFraction).toInt()
        val padY = (rect.height() * padFraction).toInt()
        val left = (rect.left - padX).coerceAtLeast(0)
        val top = (rect.top - padY).coerceAtLeast(0)
        val right = (rect.right + padX).coerceAtMost(bmp.width)
        val bottom = (rect.bottom + padY).coerceAtMost(bmp.height)
        return Bitmap.createBitmap(bmp, left, top, right - left, bottom - top)
    }

    private fun drawAnnotations(
        base: Bitmap,
        geometry: Geometry,
        arrows: List<PointF>
    ): Bitmap {
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = Color.RED
        }
        // Corners
        geometry.corners.forEach {
            c.drawCircle(it.x, it.y, 12f, paint)
        }
        // Center
        paint.color = Color.GREEN
        c.drawCircle(geometry.center.x, geometry.center.y, 14f, paint)

        // Arrows
        paint.color = Color.YELLOW
        paint.style = Paint.Style.FILL
        arrows.forEach {
            c.drawCircle(it.x, it.y, 10f, paint)
        }
        return out
    }

}
