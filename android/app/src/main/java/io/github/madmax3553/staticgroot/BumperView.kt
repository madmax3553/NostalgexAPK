package io.github.madmax3553.staticgroot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.os.SystemClock
import kotlin.math.sin

/** Local station ident, independent of Jellyfin and available even without network playback. */
class BumperView(context: Context) : View(context) {
    var channelName = ""
    var upcoming: List<String> = emptyList()
    var remainingSeconds = 30L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val logo = resources.getFont(R.font.vt323_regular)
    private val body = resources.getFont(R.font.dmmono_regular)

    init { isFocusable = false }
    private val animate = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || visibility != VISIBLE) return
            invalidate()
            postDelayed(this, 33)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) post(animate)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        removeCallbacks(animate)
        if (visibility == VISIBLE && isAttachedToWindow) post(animate)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(animate)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(GrootBrand.BACKGROUND)
        val unit = minOf(width / 640f, height / 480f)
        val center = width / 2f
        val phase = (SystemClock.elapsedRealtime() % 12000) / 12000f
        paint.color = GrootBrand.BLUE
        paint.alpha = 35
        val sweep = phase * height
        canvas.drawRect(0f, sweep, width.toFloat(), sweep + height * .06f, paint)
        paint.alpha = 255
        fun line(value: String, y: Float, size: Float, colour: Int, accent: Boolean = false) {
            paint.textSize = size * unit
            paint.typeface = if (accent) logo else body
            paint.color = colour
            paint.textAlign = Paint.Align.CENTER
            val max = width - 48 * unit
            val count = paint.breakText(value, true, max, null)
            val text = if (count < value.length) value.take((count - 1).coerceAtLeast(0)) + "…" else value
            canvas.drawText(text, center, y * height, paint)
        }
        canvas.save()
        val pulse = 1f + .025f * sin(phase * Math.PI * 2).toFloat()
        canvas.scale(pulse, pulse, center, height * .21f)
        GrootBrand.drawMark(canvas, paint, width * .12f, height * .06f, width * .76f, height * .30f)
        canvas.restore()
        line("STATICGROOT TELEVISION", .41f, 22f, GrootBrand.CYAN, true)
        line(channelName, .51f, 20f, GrootBrand.BLUE)
        line("COMING UP", .58f, 24f, Color.GRAY, true)
        upcoming.take(3).forEachIndexed { index, title ->
            line(title, .67f + index * .075f, 18f, Color.WHITE)
        }
        val countdown = "%02d:%02d".format(remainingSeconds / 60, remainingSeconds % 60)
        line("NEXT PROGRAMME IN $countdown", .94f, 20f, GrootBrand.CYAN, true)
    }
}
