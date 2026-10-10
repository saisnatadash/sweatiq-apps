package com.grassion.sweatiq.common

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/** Builds a simple A4 PDF session report. The simulation label is always printed. */
object ReportPdf {
    private const val W = 595
    private const val H = 842

    fun build(ctx: Context, s: SessionSummary, readings: List<Reading>): File {
        val doc = PdfDocument()
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
        var c = page.canvas
        val ink = Paint(Paint.ANTI_ALIAS_FLAG)
        ink.color = Color.parseColor("#1B2437")
        var y = 56f

        fun text(t: String, size: Float, bold: Boolean, x: Float = 40f, color: Int = Color.parseColor("#1B2437")) {
            ink.textSize = size
            ink.isFakeBoldText = bold
            ink.color = color
            c.drawText(t, x, y, ink)
        }

        fun newPage() {
            doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            c = page.canvas
            y = 56f
        }

        text("SweatIQ - Session Report", 22f, true, 40f, Color.parseColor("#0F9D8A"))
        y += 18f
        text("Grassion Fitness Pvt. Ltd.  |  sweatiq.grassion.com", 10f, false, 40f, Color.parseColor("#667085"))
        y += 26f
        val warn = Paint()
        warn.color = Color.parseColor("#FFF4D6")
        c.drawRect(40f, y - 14f, W - 40f, y + 12f, warn)
        text("SIMULATED DATA - NOT A BIOSENSOR MEASUREMENT", 11f, true, 48f, Color.parseColor("#8A5A00"))
        y += 34f

        text("Athlete: " + s.athleteName + " (" + s.athleteId + ")", 12f, false); y += 18f
        text("Sport: " + s.sport + "   Session: " + s.id, 12f, false); y += 18f
        text("Start: " + dateTime(s.startTs) + "   End: " + dateTime(s.endTs), 12f, false); y += 18f
        text("Duration: " + dur(s.durationMs) + "   Readings: " + s.count + "   Interruptions: " + s.interruptions, 12f, false)
        y += 30f

        text("Metric", 11f, true, 40f)
        text("Average", 11f, true, 250f)
        text("Minimum", 11f, true, 340f)
        text("Maximum", 11f, true, 430f)
        y += 8f
        val rule = Paint()
        rule.color = Color.parseColor("#D0D5DD")
        c.drawLine(40f, y, W - 40f, y, rule)
        y += 18f
        fun row(name: String, m: MetricStat, unit: String, two: Boolean) {
            fun fmt(d: Double) = if (two) f2(d) else f1(d)
            text("$name ($unit)", 11f, false, 40f)
            text(fmt(m.avg), 11f, false, 250f)
            text(fmt(m.min), 11f, false, 340f)
            text(fmt(m.max), 11f, false, 430f)
            y += 20f
        }
        row("Sodium Na+", s.na, "mmol/L", false)
        row("Potassium K+", s.k, "mmol/L", true)
        row("Chloride Cl-", s.cl, "mmol/L", false)
        row("Sweat rate", s.rate, "L/h", true)
        y += 16f

        if (readings.isNotEmpty()) {
            text("Reading log", 13f, true)
            y += 20f
            fun header() {
                text("#", 10f, true, 40f); text("Time", 10f, true, 80f)
                text("Na+", 10f, true, 180f); text("K+", 10f, true, 250f)
                text("Cl-", 10f, true, 320f); text("Rate", 10f, true, 390f)
                y += 6f
                c.drawLine(40f, y, W - 40f, y, rule)
                y += 14f
            }
            header()
            for (r in readings) {
                if (y > H - 50f) {
                    newPage()
                    header()
                }
                text(r.seq.toString(), 10f, false, 40f)
                text(clock(r.ts), 10f, false, 80f)
                text(f1(r.na), 10f, false, 180f)
                text(f2(r.k), 10f, false, 250f)
                text(f1(r.cl), 10f, false, 320f)
                text(f2(r.rate), 10f, false, 390f)
                y += 15f
            }
        }
        y = H - 28f
        text("Prototype software demonstration. Values are simulated inputs, not validated physiological measurements.", 8f, false, 40f, Color.parseColor("#667085"))
        doc.finishPage(page)

        val dir = File(ctx.cacheDir, "reports")
        dir.mkdirs()
        val f = File(dir, "SweatIQ_Report_" + s.id + ".pdf")
        FileOutputStream(f).use { doc.writeTo(it) }
        doc.close()
        return f
    }

    fun share(ctx: Context, f: File) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val i = Intent(Intent.ACTION_SEND)
        i.type = "application/pdf"
        i.putExtra(Intent.EXTRA_STREAM, uri)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "Export session report"))
    }
}
