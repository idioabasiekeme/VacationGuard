package com.example.vacationguard

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.abs

/**
 * Very small frame-differencing motion detector. The luminance (Y) plane
 * of each frame is averaged into a GRID x GRID matrix; the mean absolute
 * difference against the previous frame is the motion score.
 */
class MotionDetector(
    private val onScore: (Double) -> Unit
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
        image.close()
    }
}
