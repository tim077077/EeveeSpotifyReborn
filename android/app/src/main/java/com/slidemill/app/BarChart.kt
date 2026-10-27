package com.slidemill.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View

/** A plain bar chart: one bar per value, its label under it and its number above the tallest ones. */
class BarChart(context: Context, private val bar: Int, private val ink: Int, private val muted: Int) : View(context) {
    private var values: List<Long> = emptyList()
    private var labels: List<String> = emptyList()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bar }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    fun set(values: List<Long>, labels: List<String>) {
        this.values = values
        this.labels = labels
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (values.isEmpty()) return
        val labelH = sp(16f)
        val numberH = sp(16f)
        val top = numberH
        val bottom = height - labelH
        val slot = width.toFloat() / values.size
        val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
        values.forEachIndexed { i, v ->
            val x0 = i * slot + slot * 0.18f
            val x1 = (i + 1) * slot - slot * 0.18f
            val h = (bottom - top) * v / max
            canvas.drawRoundRect(RectF(x0, bottom - h.coerceAtLeast(if (v > 0) sp(2f) else 0f), x1, bottom), sp(3f), sp(3f), fill)
            text.color = ink
            text.textSize = sp(10f)
            if (v > 0) canvas.drawText(Fmt.compact(v), (x0 + x1) / 2, bottom - h - sp(4f), text)
            text.color = muted
            canvas.drawText(labels.getOrElse(i) { "" }, (x0 + x1) / 2, height - sp(3f), text)
        }
    }
}

object Fmt {
    fun compact(n: Long): String = when {
        n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0).replace(".0M", "M")
        n >= 10_000 -> "${n / 1000}K"
        n >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", n / 1000.0).replace(".0K", "K")
        else -> n.toString()
    }
}
