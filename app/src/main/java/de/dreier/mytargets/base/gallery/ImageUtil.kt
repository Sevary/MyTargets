package de.dreier.mytargets.base.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.squareup.picasso.Transformation
import timber.log.Timber

object ImageUtil {

    fun decodeSampledBitmapFromFile(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        try {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, options)

            var inSampleSize = 1
            val halfW = options.outWidth / 2
            val halfH = options.outHeight / 2
            while (halfW / inSampleSize > reqWidth && halfH / inSampleSize > reqHeight) {
                inSampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options()
            decodeOpts.inSampleSize = inSampleSize
            decodeOpts.inPreferredConfig = Bitmap.Config.RGB_565

            val bmp = BitmapFactory.decodeFile(path, decodeOpts)
            if (bmp == null) {
                Timber.d("decodeSampledBitmapFromFile returned null for $path")
                return null
            }
            return applyExifRotationIfNeeded(bmp, path)
        } catch (e: Throwable) {
            Timber.e(e, "decodeSampledBitmapFromFile failed for $path")
            return null
        }
    }

    fun applyExifRotationIfNeeded(bmp: Bitmap, path: String): Bitmap {
        try {
            val exif = ExifInterface(path)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                else -> return bmp
            }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            if (rotated != bmp) {
                bmp.recycle()
            }
            return rotated
        } catch (e: Exception) {
            Timber.e(e, "applyExifRotationIfNeeded: failed to apply rotation for $path")
            return bmp
        }
    }

    class ExifRotateTransformation(private val path: String) : Transformation {
        override fun transform(source: Bitmap): Bitmap {
            try {
                val rotated = applyExifRotationIfNeeded(source, path)
                if (rotated != source) {
                    try {
                        source.recycle()
                    } catch (_: Throwable) {}
                }
                return rotated
            } catch (e: Exception) {
                Timber.e(e, "ExifRotateTransformation failed for $path")
                return source
            }
        }

        override fun key(): String {
            return "exif_rotate_" + path.hashCode()
        }
    }

    fun rotate90Clockwise(bmp: Bitmap): Bitmap {
        val matrix = Matrix().apply { postRotate(90f) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    }
}
