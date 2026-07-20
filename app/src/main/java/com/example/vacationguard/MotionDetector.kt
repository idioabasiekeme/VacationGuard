package com.example.vacationguard

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.abs

/**
 * Frame-differencing motion detector + frame grabber. The luminance (Y)
 * plane of each frame is averaged into a GRID x GRID matrix; the mean
 * absolute difference against the previous frame is the motion score.
 * When [frameWanted] returns true, the frame is also converted to NV21
 * and handed to [onFrame] (used for snapshots and the live feed).
 */
class MotionDetector(
    private val onScore: (Double) -> Unit,
    private val frameWanted: (() -> Boolean)? = null,
    private val onFrame: ((nv21: ByteArray, width: Int, height: Int) -> Unit)? = null
) : ImageAnalysis.Analyzer {

    companion object { const val GRID = 8 }

    private var previous: DoubleArray? = null

    override fun analyze(image: ImageProxy) {
        val y = image.planes[0]
        val buf = y.buffer
        val rowStride = y.rowStride
        val pixStride = y.pixelStride
        val w = image.width
        val h = image.height

        val cur = DoubleArray(GRID * GRID)
        val cellW = w / GRID
        val cellH = h / GRID
        for (gy in 0 until GRID) {
            for (gx in 0 until GRID) {
                var sum = 0L
                var n = 0
                var py = gy * cellH
                while (py < (gy + 1) * cellH) {
                    var px = gx * cellW
                    while (px < (gx + 1) * cellW) {
                        sum += buf.get(py * rowStride + px * pixStride).toInt() and 0xFF
                        n++
                        px += 8   // subsample for speed
                    }
                    py += 8
                }
                cur[gy * GRID + gx] = sum.toDouble() / n
            }
        }

        previous?.let { prev ->
            var diff = 0.0
            for (i in cur.indices) diff += abs(cur[i] - prev[i])
            onScore(diff / cur.size)
        }
        previous = cur

        if (frameWanted?.invoke() == true) {
            onFrame?.invoke(toNv21(image), w, h)
        }
        image.close()
    }

    /** Converts a YUV_420_888 ImageProxy to an NV21 byte array. */
    private fun toNv21(image: ImageProxy): ByteArray {
        val w = image.width
        val h = image.height
        val nv21 = ByteArray(w * h * 3 / 2)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        var pos = 0
        val yBuf = yPlane.buffer
        for (r in 0 until h) {
            for (c in 0 until w) {
                nv21[pos++] = yBuf.get(r * yPlane.rowStride + c * yPlane.pixelStride)
            }
        }
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        for (r in 0 until h / 2) {
            for (c in 0 until w / 2) {
                nv21[pos++] = vBuf.get(r * vPlane.rowStride + c * vPlane.pixelStride)
                nv21[pos++] = uBuf.get(r * uPlane.rowStride + c * uPlane.pixelStride)
            }
        }
        return nv21
    }
}
