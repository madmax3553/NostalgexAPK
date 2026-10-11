package io.github.madmax3553.staticgroot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View

/** Scanlines and tube-edge vignette from CRTOverlayView. No timer or touch/focus interception. */
class RetroOverlay(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var vignette: Shader? = null

    init { isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w > 0 && h > 0) vignette = RadialGradient(w / 2f, h / 2f, maxOf(w, h) * .7f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, 0xB3000000.toInt()),
            floatArrayOf(0f, .43f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        paint.shader = null
        paint.color = 0x1F000000
        paint.strokeWidth = 1.5f * density
        var y = 0f
        while (y < height) {
            canvas.drawLine(0f, y, width.toFloat(), y, paint)
            y += 3f * density
        }
        paint.color = Color.WHITE
        paint.shader = vignette
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
    }
}
