package io.github.madmax3553.staticgroot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View

/** GROOT mark from the owner's groot-splash.sh; Cyberdream colours from sync-colors.sh. */
object GrootBrand {
    const val BACKGROUND = 0xFF16181A.toInt()
    const val SURFACE = 0xFF3C4048.toInt()
    const val BLUE = 0xFF5EA1FF.toInt()
    const val CYAN = 0xFF5EF1FF.toInt()
    const val YELLOW = 0xFFF1FF5E.toInt()
    const val FOREGROUND = 0xFFFFFFFF.toInt()

    val mark = listOf(
        "  ▄████  ██▀███   ▒█████   ▒█████  ▄▄▄█████▓",
        " ██▒ ▀█▒▓██ ▒ ██▒▒██▒  ██▒▒██▒  ██▒▓  ██▒ ▓▒",
        "▒██░▄▄▄░▓██ ░▄█ ▒▒██░  ██▒▒██░  ██▒▒ ▓██░ ▒░",
        "░▓█  ██▓▒██▀▀█▄  ▒██   ██░▒██   ██░░ ▓██▓ ░",
        "░▒▓███▀▒░██▓ ▒██▒░ ████▓▒░░ ████▓▒░  ▒██▒ ░",
        " ░▒   ▒ ░ ▒▓ ░▒▓░░ ▒░▒░▒░ ░ ▒░▒░▒░   ▒ ░░",
        "  ░   ░   ░▒ ░ ▒░  ░ ▒ ▒░   ░ ▒ ▒░     ░",
        "░ ░   ░   ░░   ░ ░ ░ ░ ▒  ░ ░ ░ ▒    ░",
        "      ░    ░         ░ ░      ░ ░",
    )

    fun drawMark(canvas: Canvas, paint: Paint, left: Float, top: Float, width: Float, height: Float) {
        paint.typeface = Typeface.MONOSPACE
        paint.textAlign = Paint.Align.LEFT
        paint.color = BLUE
        paint.textSize = 100f
        val measured = mark.maxOf { paint.measureText(it) }
        paint.textSize = minOf(100f * width / measured, height / (mark.size * 1.1f))
        val lineHeight = paint.textSize * 1.1f
        val actualWidth = mark.maxOf { paint.measureText(it) }
        val x = left + (width - actualWidth) / 2
        val y = top + (height - lineHeight * mark.size) / 2 - paint.fontMetrics.ascent
        mark.forEachIndexed { index, line -> canvas.drawText(line, x, y + index * lineHeight, paint) }
    }
}

class GrootLogoView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init { contentDescription = "GROOT"; isFocusable = false }
    override fun onDraw(canvas: Canvas) {
        GrootBrand.drawMark(canvas, paint, 0f, 0f, width.toFloat(), height.toFloat())
    }
}
