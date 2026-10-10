package com.grassion.sweatiq.common

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Colour system shared by both apps. Each app sets its own ACCENT at start-up. */
object Pal {
    val BG = Color.parseColor("#0A101C")
    val SURFACE = Color.parseColor("#121B2B")
    val SURFACE2 = Color.parseColor("#1A2539")
    val STROKE = Color.parseColor("#26334A")
    val TEXT = Color.parseColor("#EAF1FB")
    val MUTED = Color.parseColor("#8FA0BA")
    val NA = Color.parseColor("#FFB547")
    val K = Color.parseColor("#5BE38B")
    val CL = Color.parseColor("#5AB8FF")
    val RATE = Color.parseColor("#C58BFF")
    val OK = Color.parseColor("#34D399")
    val WARN = Color.parseColor("#FBBF24")
    val BAD = Color.parseColor("#FB7185")
    var ACCENT = Color.parseColor("#2DD4BF")
    var ON_ACCENT = Color.parseColor("#04201C")
}

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

fun Context.lp(
    w: Int = MATCH, h: Int = WRAP, weight: Float = 0f,
    l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0
): LinearLayout.LayoutParams {
    val p = LinearLayout.LayoutParams(w, h, weight)
    p.setMargins(dp(l), dp(t), dp(r), dp(b))
    return p
}

fun Context.bg(color: Int, radiusDp: Int = 14, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(color)
    g.cornerRadius = dp(radiusDp).toFloat()
    if (strokeDp > 0) g.setStroke(dp(strokeDp), strokeColor)
    return g
}

fun Context.tv(
    text: String, sp: Float = 14f, color: Int = Pal.TEXT,
    bold: Boolean = false, gravity: Int = Gravity.START
): TextView {
    val t = TextView(this)
    t.text = text
    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    t.setTextColor(color)
    t.typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD)
    else Typeface.create("sans-serif", Typeface.NORMAL)
    t.gravity = gravity
    t.includeFontPadding = false
    return t
}

fun Context.vbox(): LinearLayout {
    val l = LinearLayout(this)
    l.orientation = LinearLayout.VERTICAL
    return l
}

fun Context.hbox(): LinearLayout {
    val l = LinearLayout(this)
    l.orientation = LinearLayout.HORIZONTAL
    l.gravity = Gravity.CENTER_VERTICAL
    return l
}

fun Context.space(hDp: Int): View {
    val v = View(this)
    v.layoutParams = LinearLayout.LayoutParams(MATCH, dp(hDp))
    return v
}

fun Context.card(padDp: Int = 14): LinearLayout {
    val l = vbox()
    l.background = bg(Pal.SURFACE, 16, Pal.STROKE, 1)
    l.setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
    return l
}

/** Pill button. filled=true uses the accent colour; otherwise an outlined button. */
fun Context.button(
    label: String, filled: Boolean = true, color: Int = Pal.ACCENT,
    textColor: Int = Pal.ON_ACCENT, onClick: () -> Unit
): TextView {
    val b = tv(label, 15f, if (filled) textColor else color, true, Gravity.CENTER)
    val base = if (filled) bg(color, 14) else bg(Color.TRANSPARENT, 14, color, 1)
    b.background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), base, null)
    b.setPadding(dp(16), dp(14), dp(16), dp(14))
    b.isClickable = true
    b.setOnClickListener { onClick() }
    return b
}

fun Context.chip(text: String, color: Int = Pal.ACCENT): TextView {
    val t = tv(text, 11f, color, true, Gravity.CENTER)
    val tint = Color.argb(34, Color.red(color), Color.green(color), Color.blue(color))
    t.background = bg(tint, 20, Color.argb(90, Color.red(color), Color.green(color), Color.blue(color)), 1)
    t.setPadding(dp(10), dp(5), dp(10), dp(5))
    return t
}

fun Context.input(hint: String, value: String = "", numeric: Boolean = false): EditText {
    val e = EditText(this)
    e.setText(value)
    e.hint = hint
    e.setTextColor(Pal.TEXT)
    e.setHintTextColor(Pal.MUTED)
    e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    e.background = bg(Pal.SURFACE2, 12, Pal.STROKE, 1)
    e.setPadding(dp(14), dp(12), dp(14), dp(12))
    e.setSingleLine(true)
    e.inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    else InputType.TYPE_CLASS_TEXT
    return e
}

fun Context.fieldLabel(text: String): TextView {
    val t = tv(text.uppercase(Locale.US), 10.5f, Pal.MUTED, true)
    t.letterSpacing = 0.08f
    return t
}

/** Single-choice row of pills. Returns the container; selection changes call onSelect(index). */
fun Context.pills(options: List<String>, selected: Int, onSelect: (Int) -> Unit): HorizontalScrollView {
    val sv = HorizontalScrollView(this)
    sv.isHorizontalScrollBarEnabled = false
    val row = hbox()
    sv.addView(row)
    val views = ArrayList<TextView>()
    fun paint(sel: Int) {
        for (i in views.indices) {
            val on = i == sel
            views[i].background = if (on) bg(Pal.ACCENT, 20) else bg(Pal.SURFACE2, 20, Pal.STROKE, 1)
            views[i].setTextColor(if (on) Pal.ON_ACCENT else Pal.TEXT)
        }
    }
    for ((i, o) in options.withIndex()) {
        val t = tv(o, 13f, Pal.TEXT, true, Gravity.CENTER)
        t.setPadding(dp(16), dp(9), dp(16), dp(9))
        t.setOnClickListener { paint(i); onSelect(i) }
        row.addView(t, lp(WRAP, WRAP, 0f, 0, 0, 8, 0))
        views.add(t)
    }
    paint(selected)
    return sv
}

fun f1(d: Double): String = String.format(Locale.US, "%.1f", d)
fun f2(d: Double): String = String.format(Locale.US, "%.2f", d)
fun clock(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))
fun dateTime(ms: Long): String = SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.US).format(Date(ms))
fun dur(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
}
