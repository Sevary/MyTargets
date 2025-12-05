package de.dreier.mytargets.ml

import android.content.Context
import android.graphics.*
import android.graphics.BitmapFactory
import android.graphics.PointF
import de.dreier.mytargets.base.gallery.ImageUtil
import java.io.File

data class MlPipelineResult(
    val processedBitmap: Bitmap,
    // normalized positions in range 0..1 relative to processedBitmap (x,y pairs)
    val arrows: List<PointF>
)

class MlTargetProcessor(private val context: Context) {

    var circlesModel: TargetCirclesModel? = null
    var centerModel: TargetCenterKModel? = null
    var arrowScoreModel: ArrowScoreModel? = null
    var arrowModel: ArrowKModel? = null

    init {
        initModels()
    }

    fun close() {
        closeModels()
    }

    fun initModels() {
        circlesModel = TargetCirclesModel(context)
        centerModel = TargetCenterKModel(context)
        arrowModel = ArrowKModel(context)
        arrowScoreModel = ArrowScoreModel(context)
    }

    fun closeModels() {
        circlesModel?.close()
        centerModel?.close()
        arrowModel?.close()
        arrowScoreModel?.close()
        circlesModel = null
        centerModel = null
        arrowModel = null
        arrowScoreModel = null
    }

    fun runPipeline(imageFile: File, arrowDetectionLimit: Int = -1): MlPipelineResult? {
        // Load source image
        val src = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return null
        val img = ImageUtil.applyExifRotationIfNeeded(src, imageFile.absolutePath)

        // Target detection
        val foundTargets = detectTargets(img)
        if (foundTargets.isEmpty()) return null
        val bestTarget = foundTargets.maxByOrNull { it.score }!!
        val cropped = cropWithPadding(img, bestTarget.box, 0.2f)

        // Center detection + precise target detection (4 times average)
        val targetBounds = detectSingleTargetPrecise(cropped)!!
        val center = targetBounds.keypoint!!

        // Arrow detection
        var foundArrows = detectArrowsPrecise(cropped)
        if (arrowDetectionLimit > 0 && foundArrows.size > arrowDetectionLimit)
            foundArrows = foundArrows.sortedByDescending { it.score }.take(arrowDetectionLimit)
        val arrowsPoints: List<PointF> = foundArrows.mapNotNull { it.keypoint }

        // Convert normalized arrows to pixel coordinates for drawing
        val arrowPixels = arrowsPoints.map { p -> PointF(p.x * cropped.width, p.y * cropped.height) }

        // Draw annotations (center + corners + arrows)
        val geometry = Geometry(PointF(center.x * cropped.width, center.y * cropped.height),
            listOf(
            PointF(targetBounds.box.left * cropped.width, targetBounds.box.top * cropped.height),
            PointF(targetBounds.box.right * cropped.width, targetBounds.box.top * cropped.height),
            PointF(targetBounds.box.right * cropped.width, targetBounds.box.bottom * cropped.height),
            PointF(targetBounds.box.left * cropped.width, targetBounds.box.bottom * cropped.height)
        ))
        val annotated = drawAnnotations(cropped, geometry, arrowPixels)

        // Norm so that (0,0) is top-left of target box and (1,1) is bottom-right, with linear interpolation towards center
        val arrowsNorm = adjustArrowPositions(arrowsPoints, targetBounds.box, center)

        return MlPipelineResult(annotated, arrowsNorm)
    }

    // --- Placeholder data structures & methods ---

    private fun PointF.distance(other: PointF): Float {
        val dx = x - other.x
        val dy = y - other.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun detectSingleTargetPrecise(bmp: Bitmap, threshold: Float = 0.3f): Detection? {
        val detections = mutableListOf<Detection>()
        var bmp_rot = bmp

        // 0°, 90°, 180°, 270°
        for (i in 0 until 4) {
            val det = detectTargets(bmp_rot, threshold).firstOrNull() ?: return null
            val transformed = transformBack(det, i)
            detections.add(transformed)

            bmp_rot = ImageUtil.rotate90Clockwise(bmp_rot)
        }

        return averageDetection(detections)
    }

    private fun detectTargets(bmp: Bitmap, threshold: Float = 0.3f): List<Detection> {
        //val res = circlesModel?.process(bmp) ?: return emptyList()
        val res = centerModel?.process(bmp) ?: return emptyList()
        return res.filter { it.score >= threshold } . sortedByDescending { it.score }
    }

    private fun detectArrows(bmp: Bitmap, threshold: Float = 0.1f): List<Detection> {
        //val res = arrowModel?.process(bmp) ?: return emptyList()
        val res = arrowScoreModel?.process(bmp) ?: return emptyList()

        if (res[0].keypoint != null)
            return res.filter { it.score >= threshold } .sortedByDescending { it.score }

        val addedKeypointList = mutableListOf<Detection>()
        for (r in res.filter { it.score >= threshold }) {
            val keypoint = PointF(
                (r.box.left + r.box.right) / 2f,
                (r.box.top + r.box.bottom) / 2f
            )

            addedKeypointList.add(
                Detection(
                    score = r.score,
                    box = r.box,
                    cls = r.cls,
                    keypoint = keypoint,
                    extras = r.extras
                )
            )
        }

        return addedKeypointList
    }

    private fun detectArrowsPrecise(bmp: Bitmap, threshold: Float = 0.1f, distanceThreshold: Float = 0.05f): List<Detection> {
        val lists = mutableListOf<List<Detection>>()
        var bmp_rot = bmp

        for (i in 0 until 4) {
            val dets = detectArrows(bmp_rot, threshold)
            val transformed = transformBack(dets, i)
            lists.add(transformed)

            bmp_rot = ImageUtil.rotate90Clockwise(bmp_rot)
        }

        return mergeDetections(lists, distanceThreshold)
    }

    private fun mergeDetections(lists: List<List<Detection>>, distanceThreshold: Float = 0.05f, flatten: Boolean = false): List<Detection> {
        val numLists = lists.size
        val used = Array(numLists) { BooleanArray(lists[it].size) }
        val groups = mutableListOf<MutableList<Detection>>()

        if (flatten) {
            val allDets = lists.flatten()
            for (det in allDets)
            {
                val keypoint = det.keypoint ?: continue
                val group = groups.find { group ->
                    group.any { existing ->
                        existing.keypoint?.let { dist ->
                            keypoint.distance(dist) < distanceThreshold
                        } ?: false
                    }
                }
                if (group != null) {
                    group.add(det)
                } else {
                    val newGroup = mutableListOf(det)
                    groups.add(newGroup)
                }
            }
        } else {
            for ((i, list) in lists.withIndex()) {
                for ((j, det) in list.withIndex()) {
                    if (used[i][j]) continue
                    val group = mutableListOf(det)
                    used[i][j] = true
                    val keypoint = det.keypoint ?: continue

                    // For each other list, find the closest detection within threshold
                    for (k in 0 until numLists) {
                        if (k == i) continue
                        var minDist = Float.MAX_VALUE
                        var minIdx = -1
                        for ((l, otherDet) in lists[k].withIndex()) {
                            if (used[k][l]) continue
                            val otherKeypoint = otherDet.keypoint ?: continue
                            val dist = keypoint.distance(otherKeypoint)
                            if (dist < distanceThreshold && dist < minDist) {
                                minDist = dist
                                minIdx = l
                            }
                        }
                        if (minIdx != -1) {
                            group.add(lists[k][minIdx])
                            used[k][minIdx] = true
                        }
                    }
                    groups.add(group)
                }
            }
        }

        return groups.map { group ->
            val sumScore = group.map { it.score }.sum()
            val avgScore = sumScore / numLists.toFloat()
            val boxes = group.map { it.box }
            val avgLeft = boxes.map { it.left }.average().toFloat()
            val avgTop = boxes.map { it.top }.average().toFloat()
            val avgRight = boxes.map { it.right }.average().toFloat()
            val avgBottom = boxes.map { it.bottom }.average().toFloat()
            val avgBox = RectF(avgLeft, avgTop, avgRight, avgBottom)
            val keypoints = group.mapNotNull { it.keypoint }
            val avgKeypoint = if (keypoints.isNotEmpty()) {
                val avgX = keypoints.map { it.x }.average().toFloat()
                val avgY = keypoints.map { it.y }.average().toFloat()
                PointF(avgX, avgY)
            } else null
            Detection(
                score = avgScore,
                box = avgBox,
                cls = group.first().cls,
                keypoint = avgKeypoint,
                label = group.first().label,
                extras = group.first().extras
            )
        } . sortedByDescending { it.score }
    }

    /// Adjust arrow positions to be relative to target box and center. Uses linear interpolation.
    private fun adjustArrowPositions(arrows: List<PointF>, targetBox: RectF, targetCenter: PointF): List<PointF> {
        val adjusted = mutableListOf<PointF>()

        // Center in target box normalized coordinates
        val cx = (targetCenter.x - targetBox.left) / targetBox.width()
        val cy = (targetCenter.y - targetBox.top) / targetBox.height()

        for (arrow in arrows) {
            // Adjust to target box
            val nx = (arrow.x - targetBox.left) / targetBox.width()
            val ny = (arrow.y - targetBox.top) / targetBox.height()

            // Linear interpolation towards center from the corresponding box edges
            val ix = CustomInterp(nx, cx)
            val iy = CustomInterp(ny, cy)

            adjusted.add(PointF(ix, iy))
        }

        return adjusted
    }

    private fun CustomInterp(p: Float, c: Float): Float {
        return if (p < c) {
            0.5f * (p / c)
        } else {
            0.5f * (1 + (p - c) / (1 - c) )
        }
    }

    private fun transformBack(dets: List<Detection>, rotations: Int): List<Detection> {
        return dets.map { det -> transformBack(det, rotations) }
    }

    private fun transformBack(det: Detection, rotations: Int): Detection {
        var currentDet = det
        repeat(rotations) {
            currentDet = transformBack90(currentDet)
        }
        return currentDet
    }

    private fun transformBack90(det: Detection): Detection {
        val box = det.box
        val corners = listOf(
            PointF(box.left, box.top),
            PointF(box.right, box.top),
            PointF(box.right, box.bottom),
            PointF(box.left, box.bottom)
        )
        val transformedCorners = corners.map { p -> PointF(p.y, 1 - p.x) }
        val newLeft = transformedCorners.minOf { it.x }
        val newTop = transformedCorners.minOf { it.y }
        val newRight = transformedCorners.maxOf { it.x }
        val newBottom = transformedCorners.maxOf { it.y }
        val newBox = RectF(newLeft, newTop, newRight, newBottom)
        val newKeypoint = det.keypoint?.let { PointF(it.y, 1 - it.x) }
        return det.copy(box = newBox, keypoint = newKeypoint)
    }

    private fun averageDetection(dets: List<Detection>): Detection {
        val boxes = dets.map { it.box }
        val avgLeft = boxes.map { it.left }.average().toFloat()
        val avgTop = boxes.map { it.top }.average().toFloat()
        val avgRight = boxes.map { it.right }.average().toFloat()
        val avgBottom = boxes.map { it.bottom }.average().toFloat()
        val avgBox = RectF(avgLeft, avgTop, avgRight, avgBottom)
        val keypoints = dets.mapNotNull { it.keypoint }
        val avgKeypoint = if (keypoints.isNotEmpty()) {
            val avgX = keypoints.map { it.x }.average().toFloat()
            val avgY = keypoints.map { it.y }.average().toFloat()
            PointF(avgX, avgY)
        } else null
        return Detection(
            score = dets.map { it.score }.average().toFloat(),
            box = avgBox,
            cls = dets.first().cls,
            keypoint = avgKeypoint,
            label = dets.first().label,
            extras = dets.first().extras
        )
    }

    data class Geometry(val center: PointF, val corners: List<PointF>)

    private fun cropWithPadding(bmp: Bitmap, boxF: RectF, padFraction: Float = 0.2f): Bitmap {
        val height = bmp.height
        val width = bmp.width
        val rect = Rect(
            (boxF.left * width).toInt(),
            (boxF.top * height).toInt(),
            (boxF.right * width).toInt(),
            (boxF.bottom * height).toInt()
        )
        return cropWithPadding(bmp, rect, padFraction)
    }

    private fun cropWithPadding(bmp: Bitmap, rect: Rect, padFraction: Float = 0.2f): Bitmap {
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
            color = Color.MAGENTA
        }
        // Corners
        geometry.corners.forEach {
            c.drawCircle(it.x, it.y, 12f, paint)
        }
        // Center
        paint.color = Color.GREEN
        c.drawCircle(geometry.center.x, geometry.center.y, 24f, paint)

        // Arrows
        paint.color = Color.YELLOW
        paint.style = Paint.Style.FILL
        arrows.forEach {
            c.drawCircle(it.x, it.y, 10f, paint)
        }
        return out
    }

}
