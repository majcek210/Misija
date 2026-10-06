package dev.tester.mockgps

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * Round joystick. Reports (x, y) in -1..1, where x+ = east and y+ = north.
 * Springs back to the centre (0, 0) when released.
 */
class JoystickView(
    context: Context,
    private val onChange: (x: Float, y: Float) -> Unit,
) : View(context) {

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 55, 60, 70) }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 110, 118, 130)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 80, 170, 255) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        textAlign = Paint.Align.CENTER
        textSize = 11f * context.resources.displayMetrics.density
    }

    private var knobX = 0f // -1..1
    private var knobY = 0f // -1..1, screen down is positive

    private val radius get() = min(width, height) / 2f
    private val knobRadius get() = radius * 0.32f
    private val travel get() = radius - knobRadius

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, radius - 2f, basePaint)
        canvas.drawCircle(cx, cy, radius - 2f, ringPaint)
        val pad = labelPaint.textSize
        canvas.drawText("N", cx, cy - radius + pad + 2f, labelPaint)
        canvas.drawText("S", cx, cy + radius - pad / 2f, labelPaint)
        canvas.drawText("W", cx - radius + pad, cy + pad / 3f, labelPaint)
        canvas.drawText("E", cx + radius - pad, cy + pad / 3f, labelPaint)
        canvas.drawCircle(cx + knobX * travel, cy + knobY * travel, knobRadius, knobPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                var dx = (e.x - width / 2f) / travel
                var dy = (e.y - height / 2f) / travel
                val len = hypot(dx, dy)
                if (len > 1f) {
                    dx /= len
                    dy /= len
                }
                knobX = dx
                knobY = dy
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                knobX = 0f
                knobY = 0f
            }
            else -> return false
        }
        invalidate()
        onChange(knobX, -knobY)
        return true
    }
}
