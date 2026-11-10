package com.roywatson.android_litert_object_recog.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import java.nio.ByteBuffer
import androidx.camera.core.ImageProxy

/** A1 reference path: CameraX RGBA → upright center square → bilinear 224px RGB. */
class FramePreprocessor {
    private var source: Bitmap? = null
    private val resized = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(resized)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(SIZE * SIZE)
    private val tensor = FloatArray(SIZE * SIZE * 3)

    fun prepare(image: ImageProxy): FloatArray {
        val plane = image.planes[0]
        return prepareRgba(plane.buffer, image.width, image.height, plane.rowStride,
            plane.pixelStride, image.cropRect, image.imageInfo.rotationDegrees)
    }

    internal fun prepareRgba(data: ByteBuffer, width: Int, height: Int, rowStride: Int,
        pixelStride: Int, crop: Rect, rotationDegrees: Int): FloatArray {
        // CameraX RGBA output uses R,G,B,A bytes. Honor row padding explicitly.
        val buffer = data.duplicate()
        val bitmap = source?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                source?.recycle()
                source = it
            }
        val row = IntArray(width)
        val origin = buffer.position()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val offset = origin + y * rowStride + x * pixelStride
                val r = buffer.get(offset).toInt() and 255
                val g = buffer.get(offset + 1).toInt() and 255
                val b = buffer.get(offset + 2).toInt() and 255
                row[x] = (255 shl 24) or (r shl 16) or (g shl 8) or b
            }
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
        val scale = SIZE.toFloat() / minOf(crop.width(), crop.height())
        val matrix = Matrix().apply {
            postTranslate(-crop.exactCenterX(), -crop.exactCenterY())
            postRotate(rotationDegrees.toFloat())
            postScale(scale, scale)
            postTranslate(SIZE / 2f, SIZE / 2f)
        }
        canvas.drawBitmap(bitmap, matrix, paint)
        resized.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        pixels.forEachIndexed { index, pixel ->
            tensor[index * 3] = normalize(pixel shr 16 and 255)
            tensor[index * 3 + 1] = normalize(pixel shr 8 and 255)
            tensor[index * 3 + 2] = normalize(pixel and 255)
        }
        return tensor
    }

    fun close() { source?.recycle(); resized.recycle() }

    companion object {
        const val SIZE = 224
        // From this model's NormalizationOptions metadata: mean=127, std=128.
        fun normalize(channel: Int): Float = (channel - 127f) / 128f
    }
}
