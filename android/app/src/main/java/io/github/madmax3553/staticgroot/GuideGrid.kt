package io.github.madmax3553.staticgroot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.View
import android.os.SystemClock
import io.github.madmax3553.staticgroot.channels.ChannelRuntime
import io.github.madmax3553.staticgroot.schedule.ChannelSchedule
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Two-hour EPG viewport, matching ChannelGuideView: four half-hour columns and whole-row focus. */
class GuideGrid(context: Context, private val selected: (ChannelRuntime) -> Unit,
    private val tune: (ChannelRuntime) -> Unit, private val openSettings: () -> Unit) : View(context) {
    var channels: List<ChannelRuntime> = emptyList()
    var schedules: Map<Int, ChannelSchedule> = emptyMap()
    var windowStart = System.currentTimeMillis() / 1000 / 1800 * 1800
    private var index = 0
    private var firstRow = 0
    private var slotOffset = 0
    private var lastInput = SystemClock.elapsedRealtime()
    private var lastFrame = lastInput
    private var crawlRows = 0f
    private var crawling = false
    var crawlEnabled = false
        set(value) {
            field = value
            if (value) { lastFrame = SystemClock.elapsedRealtime(); postOnAnimation(crawl) }
            else removeCallbacks(crawl)
        }
    private val crawl = object : Runnable {
        override fun run() {
            if (!crawlEnabled || !isAttachedToWindow) return
            val now = SystemClock.elapsedRealtime()
            if (hasFocus() && channels.size > 5 && now - lastInput >= 30000) {
                if (!crawling) { crawlRows = firstRow.toFloat(); crawling = true }
                // One row in eight seconds. The preview stays on the tuned channel.
                crawlRows = (crawlRows + (now - lastFrame).coerceAtMost(100) / 8000f) % channels.size
                invalidate()
            }
            lastFrame = now
            postDelayed(this, 32)
        }
    }

    fun userInteraction(): Boolean {
        lastInput = SystemClock.elapsedRealtime()
        if (crawling) {
            index = CrawlSelection.centerIndex(crawlRows, channels.size)
            crawling = false
            firstRow = (index - 2).coerceAtLeast(0)
            keepVisible()
            selected(channels[index])
            invalidate()
            return true
        }
        return false
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(crawl)
        super.onDetachedFromWindow()
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val font = resources.getFont(R.font.dmmono_regular)
    private val accent = resources.getFont(R.font.vt323_regular)
    private val time = SimpleDateFormat("h:mm a", Locale.getDefault())

    init { isFocusable = true; isFocusableInTouchMode = true }

    fun select(id: Int) {
        val next = channels.indexOfFirst { it.id == id }.coerceAtLeast(0)
        if (next == index && crawling) return
        index = next
        keepVisible()
        invalidate()
    }

    fun returnToChannel(id: Int) {
        userInteraction()
        slotOffset = 0
        select(id)
        requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        userInteraction()
        if (channels.isEmpty()) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (index == 0) { openSettings(); return true }
                index--
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> index = (index + 1) % channels.size
            KeyEvent.KEYCODE_DPAD_LEFT -> { slotOffset = (slotOffset - 1).coerceAtLeast(0); invalidate(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { slotOffset = (slotOffset + 1).coerceAtMost(44); invalidate(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { tune(channels[index]); return true }
            else -> return super.onKeyDown(keyCode, event)
        }
        keepVisible()
        selected(channels[index])
        invalidate()
        return true
    }

    private fun keepVisible() {
        val visible = 5
        if (index < firstRow) firstRow = index
        if (index >= firstRow + visible) firstRow = index - visible + 1
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = width / 1920f
        val column = 220f * scale
        val header = 60f * scale
        val rowHeight = (height - header) / 5f
        val programmeWidth = width - column
        val from = windowStart + slotOffset * 1800
        val to = from + 7200
        canvas.drawColor(GrootBrand.BACKGROUND)
        if (channels.isEmpty()) {
            text(canvas, "NO CHANNELS ON AIR", 20f * scale, height / 2f,
                30f * scale, Color.GRAY, accent)
            return
        }
        text(canvas, "CHANNEL", 20f * scale, header * .66f, 22f * scale, Color.GRAY, accent)
        for (slot in 0..3) {
            text(canvas, time.format(Date((from + slot * 1800) * 1000)),
                column + programmeWidth * slot / 4 + 20 * scale, header * .66f,
                26f * scale, Color.WHITE, accent)
        }
        canvas.save()
        canvas.clipRect(0f, header, width.toFloat(), height.toFloat())
        val startRow = if (crawling) crawlRows.toInt() else firstRow
        val fraction = if (crawling) crawlRows - startRow else 0f
        for (row in 0 until 6) {
            val i = if (crawling) (startRow + row) % channels.size else startRow + row
            val ch = channels.getOrNull(i) ?: break
            val top = header + (row - fraction) * rowHeight
            val bottom = top + rowHeight
            val focused = i == index && hasFocus() && !crawling
            paint.color = if (focused) GrootBrand.SURFACE else
                if (i % 2 == 0) GrootBrand.BACKGROUND else Color.rgb(29, 32, 36)
            canvas.drawRect(0f, top, width.toFloat(), bottom, paint)
            val colour = runCatching { Color.parseColor(ch.definition.colorHex) }.getOrDefault(Color.YELLOW)
            text(canvas, "CH ${ch.number.toString().padStart(2, '0')}", 20 * scale,
                top + rowHeight * .38f, 28f * scale, colour, accent)
            clippedText(canvas, ch.name, 20 * scale, top + rowHeight * .75f,
                column - 32 * scale, 18f * scale, colour, font)
            val entries = schedules[ch.id]?.entries.orEmpty().filter { it.endUnix > from && it.startUnix < to }
            if (entries.isEmpty()) text(canvas, "OFF AIR", column + 20 * scale,
                top + rowHeight * .6f, 24 * scale, Color.DKGRAY, accent)
            for (entry in entries) {
                val left = column + ((maxOf(from, entry.startUnix) - from) / 7200f) * programmeWidth
                val right = column + ((minOf(to, entry.endUnix) - from) / 7200f) * programmeWidth
                paint.color = Color.rgb(55, 55, 70)
                canvas.drawRect(right - scale, top, right, bottom, paint)
                clippedText(canvas, if (entry.block.isBumper) "STATION BREAK" else entry.item.title, left + 16 * scale, top + rowHeight * .43f,
                    right - left - 28 * scale, 24 * scale, Color.WHITE, font)
                val metadata = entry.item.seTag ?: entry.item.year?.toString().orEmpty()
                clippedText(canvas, metadata, left + 16 * scale, top + rowHeight * .76f,
                    right - left - 28 * scale, 20 * scale, Color.GRAY, accent)
            }
            if (focused) {
                paint.color = GrootBrand.BLUE; paint.style = Paint.Style.STROKE; paint.strokeWidth = 3 * scale
                canvas.drawRect(RectF(2 * scale, top + scale, width - 2 * scale, bottom - scale), paint)
                paint.style = Paint.Style.FILL
            }
        }
        canvas.restore()
        val now = System.currentTimeMillis() / 1000
        if (now in from..to) {
            paint.color = GrootBrand.CYAN
            val x = column + (now - from) / 7200f * programmeWidth
            canvas.drawRect(x, header, x + 2 * scale, height.toFloat(), paint)
        }
    }

    private fun text(c: Canvas, text: String, x: Float, y: Float, size: Float, colour: Int, font: Typeface) {
        paint.textSize = size; paint.color = colour; paint.typeface = font
        c.drawText(text, x, y, paint)
    }

    private fun clippedText(c: Canvas, value: String, x: Float, y: Float, available: Float,
        size: Float, colour: Int, font: Typeface) {
        if (available < 8) return
        paint.textSize = size; paint.typeface = font
        val count = paint.breakText(value, true, available, null)
        val label = if (count < value.length && count > 2) value.take(count - 2) + "…" else value.take(count)
        text(c, label, x, y, size, colour, font)
    }
}
