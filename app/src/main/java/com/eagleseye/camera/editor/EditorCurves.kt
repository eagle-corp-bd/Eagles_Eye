package com.eagleseye.camera.editor


/** Curves engine: control points -> 256-value LUT rows via a monotone cubic
 *  spline (Fritsch–Carlson), plus the classic Snapseed-style curve presets. */
object EditorCurves {

    const val LUM = 0
    const val R = 1
    const val G = 2
    const val B = 3

    val CHANNELS = listOf(LUM to "RGB", R to "RED", G to "GREEN", B to "BLUE")

    fun linear(): FloatArray = FloatArray(256) { it / 255f }

    /** Sample a monotone cubic through the given control points (any order;
     *  sorted by x internally). Returns 256 values in 0..1. */
    fun sample(points: List<FloatArray>): FloatArray {
        val pts = points.sortedBy { it[0] }
        val n = pts.size
        val x = FloatArray(n); val y = FloatArray(n)
        for (i in 0 until n) { x[i] = pts[i][0].coerceIn(0f, 255f); y[i] = pts[i][1].coerceIn(0f, 255f) }
        if (n == 1) return FloatArray(256) { y[0] / 255f }
        // ensure endpoints
        val dx = FloatArray(n); val m = FloatArray(n)
        for (i in 0 until n - 1) dx[i] = x[i + 1] - x[i]
        for (i in 0 until n - 1) m[i] = (y[i + 1] - y[i]) / dx[i]
        // Fritsch–Carlson slope limiting
        val mOut = FloatArray(n)
        mOut[0] = m[0]
        for (i in 1 until n - 1) {
            if (m[i - 1] * m[i] < 0f) mOut[i] = 0f
            else {
                mOut[i] = 3f * (dx[i - 1] + dx[i]) / maxOf(dx[i - 1] + dx[i], 1e-6f) * (2f * m[i - 1] * m[i] / maxOf(m[i - 1] + m[i], 1e-6f))
            }
        }
        mOut[n - 1] = m[n - 2]
        val out = FloatArray(256)
        var seg = 0
        for (i in 0 until 256) {
            val t = i.toFloat() / 255f * (x[n - 1] - x[0]) + x[0]
            while (seg < n - 2 && t > x[seg + 1]) seg++
            val segT = (t - x[seg]) / maxOf(dx[seg], 1e-6f)
            val h00 = 2f * segT * segT * segT - 3f * segT * segT + 1f
            val h10 = segT * segT * segT - 2f * segT * segT + segT
            val h01 = -2f * segT * segT * segT + 3f * segT * segT
            val h11 = segT * segT * segT - segT * segT
            val idx = i
            out[idx] = (h00 * y[seg] + h10 * dx[seg] * mOut[seg] + h01 * y[seg + 1] + h11 * dx[seg] * mOut[seg + 1]).coerceIn(0f, 255f) / 255f
        }
        return out
    }

    /** Presets as (name, control points) for any channel. */
    val PRESETS: List<Pair<String, List<FloatArray>>> = listOf(
        "LINEAR" to listOf(floatArrayOf(0f, 0f), floatArrayOf(255f, 255f)),
        "CONTRAST" to listOf(floatArrayOf(0f, 0f), floatArrayOf(88f, 42f), floatArrayOf(170f, 168f), floatArrayOf(255f, 255f)),
        "SOFT" to listOf(floatArrayOf(0f, 14f), floatArrayOf(90f, 105f), floatArrayOf(175f, 150f), floatArrayOf(255f, 247f)),
        "STRONG" to listOf(floatArrayOf(0f, 0f), floatArrayOf(64f, 16f), floatArrayOf(192f, 216f), floatArrayOf(255f, 255f)),
        "SHADOWS" to listOf(floatArrayOf(0f, 46f), floatArrayOf(128f, 138f), floatArrayOf(255f, 250f)),
        "HIGHLIGHTS" to listOf(floatArrayOf(0f, 8f), floatArrayOf(128f, 122f), floatArrayOf(255f, 205f)),
        "BLEACH" to listOf(floatArrayOf(0f, 26f), floatArrayOf(140f, 128f), floatArrayOf(255f, 252f)),
        "PASTEL" to listOf(floatArrayOf(0f, 40f), floatArrayOf(150f, 148f), floatArrayOf(255f, 228f))
    )
}

