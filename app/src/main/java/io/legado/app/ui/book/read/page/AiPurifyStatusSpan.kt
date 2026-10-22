package io.legado.app.ui.book.read.page

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.style.ReplacementSpan
import android.view.View
import io.legado.app.R

/** Inline AI source label appended immediately after the page-number text. */
class AiPurifyStatusSpan(
    context: Context,
    private val target: View,
    private val state: AiPurifyDisplayState
) : ReplacementSpan(), Runnable {

    private val density = context.resources.displayMetrics.density
    private val text = context.getString(
        when (state) {
            AiPurifyDisplayState.ORIGINAL -> R.string.ai_reader_original_status
            AiPurifyDisplayState.RUNNING -> R.string.ai_reader_purifying_status
            AiPurifyDisplayState.PURIFIED -> R.string.ai_reader_purified_status
        }
    )
    private val spinnerSize = 11f * density
    private val spinnerGap = 4f * density
    private var rotation = 0f
    private var running = false

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        val spinnerWidth = if (state == AiPurifyDisplayState.RUNNING) {
            spinnerSize + spinnerGap
        } else {
            0f
        }
        return (spinnerWidth + paint.measureText(this.text)).toInt().coerceAtLeast(1)
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        var textStart = x
        if (state == AiPurifyDisplayState.RUNNING) {
            val spinnerTop = top + (bottom - top - spinnerSize) / 2f
            val spinnerBounds = RectF(
                x,
                spinnerTop,
                x + spinnerSize,
                spinnerTop + spinnerSize
            )
            val oldStyle = paint.style
            val oldStrokeWidth = paint.strokeWidth
            val oldStrokeCap = paint.strokeCap
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f * density
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawArc(spinnerBounds, rotation, 275f, false, paint)
            paint.style = oldStyle
            paint.strokeWidth = oldStrokeWidth
            paint.strokeCap = oldStrokeCap
            textStart += spinnerSize + spinnerGap
        }
        canvas.drawText(this.text, textStart, y.toFloat(), paint)
    }

    fun start() {
        if (state != AiPurifyDisplayState.RUNNING || running) return
        running = true
        target.removeCallbacks(this)
        target.post(this)
    }

    fun stop() {
        running = false
        target.removeCallbacks(this)
    }

    override fun run() {
        if (!running || !target.isAttachedToWindow) {
            running = false
            return
        }
        rotation = (rotation + ROTATION_STEP) % 360f
        target.invalidate()
        target.postDelayed(this, FRAME_DELAY_MS)
    }

    companion object {
        private const val FRAME_DELAY_MS = 16L
        private const val ROTATION_STEP = 12f
    }
}
