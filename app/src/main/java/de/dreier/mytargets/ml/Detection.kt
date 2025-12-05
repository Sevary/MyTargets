package de.dreier.mytargets.ml

import android.graphics.PointF
import android.graphics.RectF

data class Detection(
    val score: Float,
    val box: RectF,
    val cls: Int,
    val keypoint: PointF? = null,
    val label: String? = null,
    val extras: Map<String, Any> = emptyMap()
)

