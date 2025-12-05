package de.dreier.mytargets.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import de.dreier.mytargets.ml.ArrowScore


class ArrowScoreModel(context: Context) {
    private val model: ArrowScore = ArrowScore.newInstance(context)
    private val imgSize = 640

    private val labels = listOf("0", "1", "10", "2", "3", "4", "5", "6", "7", "8", "9")


    // Image preprocessing is encapsulated in the model wrapper
    private val imageProcessor: ImageProcessor = ImageProcessor.Builder()
        .add(ResizeOp(imgSize, imgSize, ResizeOp.ResizeMethod.BILINEAR))
        .add(NormalizeOp(0f, 255f))
        .build()

    fun process(bitmap: Bitmap): List<Detection> {
        // Build TensorImage (FLOAT32) and preprocess it
        val tensorImage = TensorImage(DataType.FLOAT32)
        tensorImage.load(bitmap)
        val processed = imageProcessor.process(tensorImage)

        // Create input buffer and run model
        val inputBuffer = TensorBuffer.createFixedSize(intArrayOf(1, imgSize, imgSize, 3), DataType.FLOAT32)
        inputBuffer.loadBuffer(processed.tensorBuffer.buffer)

        val outputs = model.process(inputBuffer)
        val outputBuffer = outputs.outputFeature0AsTensorBuffer
        val out = outputBuffer.floatArray
        val shape = outputBuffer.shape

        val stride = if (shape.isNotEmpty()) shape.last() else 6
        val num = if (stride > 0) out.size / stride else 0

        val detections = ArrayList<Detection>()
        for (i in 0 until num) {
            val base = i * stride
            // assumed ordering: [xMin, yMin, xMax, yMax, score, class]
            val xMin = out[base + 0]
            val yMin = out[base + 1]
            val xMax = out[base + 2]
            val yMax = out[base + 3]
            val score = if (stride > 4) out[base + 4] else 1.0f
            val cls = if (stride > 5) out[base + 5].toInt() else -1

            // Skip low-confidence detections
            if (score < 0.01f)
                continue

            val label = if (cls in labels.indices) labels[cls] else null

            val rect = RectF(xMin, yMin, xMax, yMax)
            detections.add(Detection(score, rect, cls, null, label))
        }

        return detections.sortedByDescending { it.score }
    }

    fun close() {
        model.close()
    }
}
