package com.grassion.sweatiq.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * Light-weight line chart. Each series is auto-scaled on its own when normalizeEach is true
 * (used for the combined "live trends" view where Na/K/Cl/sweat-rate have different magnitudes).
 */
class LineChartView(context: Context) : View(context) {
    private var series: List<List<Float>> = emptyList()
    private var colors: List<Int> = emptyList()
    var showGrid = true
    var fillUnder = false
    var normalizeEach = false
    var strokeDp = 2f

    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        line.style = Paint.Style.STROKE
        line.strokeCap = Paint.Cap.ROUND
        line.strokeJoin = Paint.Join.ROUND
        grid.color = Color.parseColor("#1F2B40")
        grid.strokeWidth = 1f
        dot.style = Paint.Style.FILL
    }

    fun set(s: List<List<Float>>, c: List<Int>) {
        series = s
        colors = c
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val padV = 6f * resources.displayMetrics.density
        val padH = 4f * resources.displayMetrics.density
        if (showGrid) {
            for (i in 0..3) {
                val y = padV + (h - 2 * padV) * i / 3f
                canvas.drawLine(0f, y, w, y, grid)
            }
        }
        var gMin = Float.MAX_VALUE
        var gMax = -Float.MAX_VALUE
        if (!normalizeEach) {
            for (s in series) for (v in s) {
                if (v < gMin) gMin = v
                if (v > gMax) gMax = v
            }
            if (gMin > gMax) return
            if (gMax - gMin < 0.001f) { gMax += 1f; gMin -= 1f }
            val pad = (gMax - gMin) * 0.15f
            gMin -= pad
            gMax += pad
        }
        line.strokeWidth = strokeDp * resources.displayMetrics.density
        for ((idx, s) in series.withIndex()) {
            if (s.size < 2) continue
            var mn = gMin
            var mx = gMax
            if (normalizeEach) {
                mn = Float.MAX_VALUE
                mx = -Float.MAX_VALUE
                for (v in s) { if (v < mn) mn = v; if (v > mx) mx = v }
                if (mx - mn < 0.001f) { mx += 1f; mn -= 1f }
                val pad = (mx - mn) * 0.2f
                mn -= pad
                mx += pad
            }
            val col = colors.getOrElse(idx) { Pal.ACCENT }
            val path = Path()
            val n = s.size
            var lastX = 0f
            var lastY = 0f
            for (i in 0 until n) {
                val x = padH + (w - 2 * padH) * i / (n - 1).toFloat()
                val y = padV + (h - 2 * padV) * (1f - (s[i] - mn) / (mx - mn))
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                lastX = x
                lastY = y
            }
            if (fillUnder) {
                val fp = Path(path)
                fp.lineTo(lastX, h)
                fp.lineTo(padH, h)
                fp.close()
                fill.shader = LinearGradient(
                    0f, 0f, 0f, h,
                    Color.argb(70, Color.red(col), Color.green(col), Color.blue(col)),
                    Color.argb(0, Color.red(col), Color.green(col), Color.blue(col)),
                    Shader.TileMode.CLAMP
                )
                canvas.drawPath(fp, fill)
            }
            line.color = col
            canvas.drawPath(path, line)
            dot.color = col
            canvas.drawCircle(lastX, lastY, 3.2f * resources.displayMetrics.density, dot)
        }
    }
}

/** SweatIQ drop mark + optional wordmark, drawn in code (no image assets needed). */
class LogoView(context: Context, private val withWord: Boolean = true) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val dropH = if (withWord) h * 0.55f else h
        val r = dropH * 0.30f
        val cx = w / 2f
        val cy = dropH * 0.60f
        val path = Path()
        path.moveTo(cx, cy - 1.55f * r)
        path.cubicTo(cx + 0.25f * r, cy - 0.95f * r, cx + r, cy - 0.35f * r, cx + r, cy + 0.25f * r)
        path.arcTo(RectF(cx - r, cy - 0.75f * r, cx + r, cy + 1.25f * r), 0f, 180f)
        path.cubicTo(cx - r, cy - 0.35f * r, cx - 0.25f * r, cy - 0.95f * r, cx, cy - 1.55f * r)
        path.close()
        p.shader = LinearGradient(
            cx, cy - 1.5f * r, cx, cy + 1.3f * r,
            Color.parseColor("#7CF2E4"), Pal.ACCENT, Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, p)
        p.shader = null
        p.color = Pal.BG
        canvas.drawCircle(cx + 0.02f * r, cy + 0.38f * r, 0.42f * r, p)
        p.color = Pal.ACCENT
        canvas.drawCircle(cx + 0.02f * r, cy + 0.38f * r, 0.20f * r, p)
        if (withWord) {
            t.textSize = h * 0.24f
            t.isFakeBoldText = true
            t.textAlign = Paint.Align.CENTER
            val sweat = "Sweat"
            val iq = "IQ"
            val wSweat = t.measureText(sweat)
            val wIq = t.measureText(iq)
            val x0 = cx - (wSweat + wIq) / 2f
            val y = h * 0.93f
            t.textAlign = Paint.Align.LEFT
            t.color = Pal.TEXT
            canvas.drawText(sweat, x0, y, t)
            t.color = Pal.ACCENT
            canvas.drawText(iq, x0 + wSweat, y, t)
        }
    }
}
