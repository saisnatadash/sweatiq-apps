package com.grassion.sweatiq.device

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.grassion.sweatiq.common.*

/**
 * SweatIQ Device App - the phone the evaluator sees.
 * It shows the pod connection, live electrolyte monitoring and the session report.
 * Readings arrive over the live link from the SweatIQ Control app (simulated data).
 */
class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private val h = Handler(Looper.getMainLooper())
    private var back: (() -> Unit)? = null

    // ----- session -----
    private var athleteId = "ATH-001"
    private var athleteName = "Demo Athlete"
    private var age = "24"
    private var sport = "Running"
    private var sessType = "Training"
    private var code = ""
    private var link: Link? = null
    private var relayUp = false
    private var relayLabel = "connecting"

    // pod pairing state (kept while the app stays open)
    private var podConnected = false
    private var podName = "SIQ-7823"

    private val readings = ArrayList<Reading>()
    private var lastSeq = 0
    private var lastTs = 0L
    private var missing = 0
    private var drops = 0
    private var liveActive = false
    private var paused = false
    private var startTs = 0L
    private var firstReadingTs = 0L
    private var pausedAccum = 0L
    private var pauseStart = 0L
    private var ended = false

    // ----- live screen views -----
    private var bannerDot: View? = null
    private var bannerText: TextView? = null
    private var timerTv: TextView? = null
    private var lastUpdateTv: TextView? = null
    private var cardNa: MetricCard? = null
    private var cardK: MetricCard? = null
    private var cardCl: MetricCard? = null
    private var cardRate: MetricCard? = null
    private var liveChart: LineChartView? = null
    private var skinChip: TextView? = null
    private var trendNa: LineChartView? = null
    private var trendK: LineChartView? = null
    private var trendCl: LineChartView? = null
    private var trendRate: LineChartView? = null
    private var rowsBox: LinearLayout? = null
    private var relayChip: TextView? = null
    private var operatorLinked = false
    private var codeLinkChip: TextView? = null
    private var codeOpChip: TextView? = null

    /** Expanding rings shown while scanning for the pod. */
    private class PulseView(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var t = 0f
        private val anim = ValueAnimator.ofFloat(0f, 1f)

        init {
            anim.duration = 1800
            anim.repeatCount = ValueAnimator.INFINITE
            anim.addUpdateListener {
                t = it.animatedValue as Float
                invalidate()
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            anim.start()
        }

        override fun onDetachedFromWindow() {
            anim.cancel()
            super.onDetachedFromWindow()
        }

        override fun onDraw(c: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val maxR = Math.min(cx, cy)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            for (i in 0..2) {
                val f = (t + i / 3f) % 1f
                paint.color = Color.argb(((1f - f) * 160).toInt(), 45, 212, 191)
                c.drawCircle(cx, cy, maxR * (0.3f + 0.7f * f), paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = Pal.ACCENT
            c.drawCircle(cx, cy, maxR * 0.2f, paint)
        }
    }

    private class MetricCard(val value: TextView, val delta: TextView, val spark: LineChartView)

    private val tick = object : Runnable {
        override fun run() {
            if (liveActive) {
                timerTv?.text = dur(elapsed())
                refreshBanner()
                h.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Pal.ACCENT = Color.parseColor("#2DD4BF")
        Pal.ON_ACCENT = Color.parseColor("#04201C")
        window.statusBarColor = Pal.BG
        window.navigationBarColor = Pal.BG
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this)
        root.setBackgroundColor(Pal.BG)
        setContentView(root)
        newCode()
        showCodeScreen()
    }

    override fun onBackPressed() {
        val b = back
        if (b != null) b() else finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        link?.stop()
        h.removeCallbacksAndMessages(null)
    }

    // ------------------------------------------------------------------ helpers

    private fun show(v: View, scroll: Boolean = true) {
        root.removeAllViews()
        if (scroll) {
            val sv = ScrollView(this)
            sv.isFillViewport = true
            sv.addView(v)
            root.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
        } else {
            root.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        }
    }

    private fun page(): LinearLayout {
        val l = vbox()
        l.setPadding(dp(20), dp(44), dp(20), dp(24))
        return l
    }

    private fun header(title: String, sub: String? = null): LinearLayout {
        val l = vbox()
        l.addView(tv(title, 26f, Pal.TEXT, true))
        if (sub != null) l.addView(tv(sub, 13f, Pal.MUTED), lp(MATCH, WRAP, 0f, 0, 6, 0, 0))
        l.addView(space(16))
        return l
    }

    private fun prefs() = getSharedPreferences("sweatiq_device", Context.MODE_PRIVATE)

    private fun history(): MutableList<SessionSummary> =
        SessionSummary.listFromJson(prefs().getString("history", null))

    private fun saveHistory(s: SessionSummary) {
        val l = history()
        l.add(0, s)
        while (l.size > 25) l.removeAt(l.size - 1)
        prefs().edit().putString("history", SessionSummary.listToJson(l)).apply()
    }

    private fun elapsed(): Long {
        if (firstReadingTs == 0L) return 0L
        val now = if (paused) pauseStart else System.currentTimeMillis()
        return (now - firstReadingTs - pausedAccum).coerceAtLeast(0)
    }

    // ------------------------------------------------------------------ 1. splash

    private fun showSplash() {
        back = null
        val l = vbox()
        l.gravity = Gravity.CENTER
        l.setPadding(dp(40), 0, dp(40), 0)
        val logo = LogoView(this)
        l.addView(logo, lp(dp(220), dp(190)))
        l.addView(tv("YOUR SWEAT. DECODED.", 13f, Pal.MUTED, true, Gravity.CENTER).also { it.letterSpacing = 0.18f },
            lp(MATCH, WRAP, 0f, 0, 6, 0, 0))
        l.addView(space(40))
        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        pb.max = 100
        pb.progressTintList = android.content.res.ColorStateList.valueOf(Pal.ACCENT)
        pb.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Pal.STROKE)
        l.addView(pb, lp(dp(200), dp(6)))
        l.addView(tv("Initialising...", 12f, Pal.MUTED, false, Gravity.CENTER), lp(MATCH, WRAP, 0f, 0, 12, 0, 0))
        show(l, false)
        val a = ValueAnimator.ofInt(0, 100)
        a.duration = 2000
        a.addUpdateListener { pb.progress = it.animatedValue as Int }
        a.start()
        h.postDelayed({ showHome() }, 2200)
    }

    // ------------------------------------------------------------------ 1b. control code (shown once, at the very start)

    private fun newCode() {
        code = (100000 + (Math.random() * 899999).toInt()).toString()
        operatorLinked = false
        startLink()
    }

    private fun refreshCodeChips() {
        codeLinkChip?.let {
            it.text = if (relayUp) "DATA CHANNEL - CONNECTED" else "DATA CHANNEL - CONNECTING"
        }
        codeOpChip?.let {
            it.text = if (operatorLinked) "OPERATOR LINKED" else "WAITING FOR OPERATOR"
            it.setTextColor(if (operatorLinked) Pal.OK else Pal.WARN)
        }
    }

    private fun showCodeScreen() {
        back = null
        val p = page()
        p.addView(header("Connect to Control", "Do this first - everything else comes after"))

        val c = card(18)
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.addView(fieldLabel("Enter this code on the SweatIQ Control phone"))
        val ct = tv(code.substring(0, 3) + " " + code.substring(3), 44f, Pal.ACCENT, true, Gravity.CENTER)
        ct.letterSpacing = 0.12f
        c.addView(ct, lp(MATCH, WRAP, 0f, 0, 14, 0, 14))
        val chips = vbox()
        chips.gravity = Gravity.CENTER_HORIZONTAL
        val a = chip("DATA CHANNEL - CONNECTING", Pal.OK)
        val b = chip("WAITING FOR OPERATOR", Pal.WARN)
        codeLinkChip = a
        codeOpChip = b
        chips.addView(a, lp(WRAP, WRAP, 0f, 0, 0, 0, 8))
        chips.addView(b, lp(WRAP, WRAP))
        c.addView(chips)
        p.addView(c)
        p.addView(space(14))

        val how = card()
        how.addView(tv("How it works", 14f, Pal.TEXT, true))
        how.addView(tv("1. On the Control phone, open SweatIQ Control and type this code.", 12f, Pal.MUTED), lp(MATCH, WRAP, 0f, 0, 8, 0, 0))
        how.addView(tv("2. Wait until this screen says OPERATOR LINKED.", 12f, Pal.MUTED), lp(MATCH, WRAP, 0f, 0, 4, 0, 0))
        how.addView(tv("3. Tap Continue. The code will not appear again after this.", 12f, Pal.MUTED), lp(MATCH, WRAP, 0f, 0, 4, 0, 0))
        p.addView(how)
        p.addView(space(18))

        p.addView(button("Continue") {
            codeLinkChip = null
            codeOpChip = null
            showSplash()
        })
        p.addView(space(8))
        p.addView(button("Generate new code", false) {
            newCode()
            showCodeScreen()
        })
        show(p)
        refreshCodeChips()
    }

    // ------------------------------------------------------------------ 2. home

    private fun showHome() {
        back = null
        resetSessionState()
        val p = page()
        val top = hbox()
        top.addView(tv("SweatIQ", 22f, Pal.TEXT, true), lp(0, WRAP, 1f))
        top.addView(chip("DEMO MODE", Pal.WARN))
        p.addView(top)
        p.addView(space(20))

        p.addView(deviceStatusCard())
        p.addView(space(10))
        p.addView(chip(if (operatorLinked) "OPERATOR LINKED" else "OPERATOR NOT LINKED YET",
            if (operatorLinked) Pal.OK else Pal.WARN), lp(WRAP, WRAP))
        p.addView(space(16))

        p.addView(button("Start New Session") { showRegister() })
        p.addView(space(10))
        p.addView(button("Session History", false) { showHistory() })
        p.addView(space(20))

        val hs = history()
        if (hs.isNotEmpty()) {
            val s = hs[0]
            val c = card()
            c.addView(fieldLabel("Last session"))
            c.addView(tv(s.athleteName + " - " + s.sport, 16f, Pal.TEXT, true), lp(MATCH, WRAP, 0f, 0, 6, 0, 0))
            c.addView(tv(dateTime(s.startTs) + "  |  " + dur(s.durationMs) + "  |  " + s.count + " readings", 12f, Pal.MUTED),
                lp(MATCH, WRAP, 0f, 0, 4, 0, 10))
            c.addView(button("View report", false) { showReport(s, null) })
            p.addView(c)
        }
        p.addView(space(24))
        p.addView(tv("Prototype build - sensor values on this screen are simulated.", 11f, Pal.MUTED, false, Gravity.CENTER))
        show(p)
    }

    private fun deviceStatusCard(): LinearLayout {
        val dev = card()
        val drow = hbox()
        val pod = vbox()
        pod.addView(tv("SweatIQ Pod", 18f, Pal.TEXT, true))
        if (podConnected) {
            pod.addView(tv(podName + " - Connected, ready", 12f, Pal.OK, true), lp(MATCH, WRAP, 0f, 0, 4, 0, 0))
        } else {
            pod.addView(tv("Not connected - pair your pod", 12f, Pal.MUTED), lp(MATCH, WRAP, 0f, 0, 4, 0, 0))
        }
        drow.addView(LogoView(this, false), lp(dp(54), dp(54), 0f, 0, 0, 14, 0))
        drow.addView(pod, lp(0, WRAP, 1f))
        if (podConnected) drow.addView(chip("Battery 87%", Pal.OK))
        dev.addView(drow)
        dev.addView(space(12))
        val st = hbox()
        val on = podConnected
        st.addView(chip(if (on) "Na+ ready" else "Na+ --", if (on) Pal.NA else Pal.MUTED), lp(WRAP, WRAP, 0f, 0, 0, 6, 0))
        st.addView(chip(if (on) "K+ ready" else "K+ --", if (on) Pal.K else Pal.MUTED), lp(WRAP, WRAP, 0f, 0, 0, 6, 0))
        st.addView(chip(if (on) "Cl- ready" else "Cl- --", if (on) Pal.CL else Pal.MUTED))
        dev.addView(st)
        dev.addView(space(12))
        dev.addView(button(if (on) "Manage pod" else "Connect Pod", false) { showPairing(false) })
        return dev
    }

    private fun resetSessionState() {
        readings.clear()
        lastSeq = 0
        lastTs = 0
        missing = 0
        drops = 0
        liveActive = false
        paused = false
        firstReadingTs = 0
        pausedAccum = 0
        ended = false
        h.removeCallbacks(tick)
        // the data channel stays up between sessions; just tell the operator we are idle again
        if (relayUp) {
            link?.send("reading", "", true)
            publishState("WAITING")
        }
    }

    // ------------------------------------------------------------------ 3. athlete registration

    private fun showRegister() {
        back = { showHome() }
        val p = page()
        p.addView(header("New Session", "Enter athlete and session details"))

        val idF = input("ATH-001", athleteId)
        val nameF = input("Athlete name", athleteName)
        val ageF = input("Age", age, true)
        p.addView(fieldLabel("Athlete ID")); p.addView(idF, lp(MATCH, WRAP, 0f, 0, 6, 0, 14))
        p.addView(fieldLabel("Name")); p.addView(nameF, lp(MATCH, WRAP, 0f, 0, 6, 0, 14))
        p.addView(fieldLabel("Age")); p.addView(ageF, lp(MATCH, WRAP, 0f, 0, 6, 0, 14))

        val sports = listOf("Running", "Cycling", "Football", "HIIT", "Tennis", "Other")
        p.addView(fieldLabel("Sport / activity"))
        p.addView(pills(sports, sports.indexOf(sport).coerceAtLeast(0)) { sport = sports[it] }, lp(MATCH, WRAP, 0f, 0, 8, 0, 14))
        val types = listOf("Training", "Match", "Other")
        p.addView(fieldLabel("Session type"))
        p.addView(pills(types, types.indexOf(sessType).coerceAtLeast(0)) { sessType = types[it] }, lp(MATCH, WRAP, 0f, 0, 8, 0, 24))

        p.addView(button("Next") {
            athleteId = idF.text.toString().trim().ifEmpty { "ATH-001" }
            athleteName = nameF.text.toString().trim().ifEmpty { "Demo Athlete" }
            age = ageF.text.toString().trim().ifEmpty { "24" }
            showConnect()
        })
        show(p)
    }

    // ------------------------------------------------------------------ 4. device connection

    private fun startLink() {
        link?.stop()
        relayUp = false
        startTs = System.currentTimeMillis()
        val l = Link(code, "device", listOf("reading", "cmd", "hello"),
            { up, label -> onRelayStatus(up, label) },
            { kind, payload -> onRemote(kind, payload) })
        link = l
        l.start()
    }

    private fun publishState(state: String) {
        val o = org.json.JSONObject()
        o.put("state", state)
        o.put("athleteId", athleteId)
        o.put("athleteName", athleteName)
        o.put("sport", sport)
        o.put("ts", System.currentTimeMillis())
        link?.send("state", o.toString(), true)
    }

    private fun onRelayStatus(up: Boolean, label: String) {
        val was = relayUp
        relayUp = up
        relayLabel = label
        refreshCodeChips()
        relayChip?.let {
            it.text = if (up) "DATA CHANNEL - CONNECTED" else "DATA CHANNEL - RECONNECTING"
        }
        if (up) {
            // wipe stale retained readings from an earlier run of the same code, then announce the session
            link?.send("reading", "", true)
            publishState(if (liveActive) (if (paused) "PAUSED" else "LIVE") else "WAITING")
        } else if (was && liveActive && !ended) {
            drops++
            refreshBanner()
        }
    }

    private fun showConnect() = showPairing(true)

    /** Pod pairing: scanning -> available pods -> connecting -> Device Ready. */
    private fun showPairing(forSession: Boolean) {
        if (forSession) back = { showRegister() } else back = { showHome() }
        val p = page()
        p.addView(header("Connect Your SweatIQ Pod", "Make sure the pod is powered on and nearby"))

        val area = vbox()
        p.addView(area)
        show(p)

        val names = listOf("SIQ-7823", "SIQ-5610")
        val strengths = listOf("Strong", "Weak")
        val strengthColors = listOf(Pal.OK, Pal.WARN)
        var sel = 0

        fun render(phase: Int) {
            area.removeAllViews()
            when (phase) {
                0 -> {
                    val c = card()
                    c.gravity = Gravity.CENTER_HORIZONTAL
                    c.addView(PulseView(this), lp(dp(170), dp(170)))
                    c.addView(tv("Scanning for nearby SweatIQ pods...", 14f, Pal.MUTED, false, Gravity.CENTER),
                        lp(MATCH, WRAP, 0f, 0, 8, 0, 6))
                    area.addView(c)
                    h.postDelayed({ render(1) }, 2000)
                }
                1 -> {
                    area.addView(tv("Available devices", 15f, Pal.TEXT, true), lp(MATCH, WRAP, 0f, 0, 0, 0, 10))
                    for (i in names.indices) {
                        val row = card(12)
                        row.background = if (i == sel) bg(Color.argb(30, 45, 212, 191), 16, Pal.ACCENT, 1)
                        else bg(Pal.SURFACE, 16, Pal.STROKE, 1)
                        val hb = hbox()
                        hb.addView(LogoView(this, false), lp(dp(40), dp(40), 0f, 0, 0, 12, 0))
                        val col = vbox()
                        col.addView(tv("SweatIQ Pod", 15f, Pal.TEXT, true))
                        col.addView(tv(names[i], 12f, Pal.MUTED), lp(MATCH, WRAP, 0f, 0, 3, 0, 0))
                        hb.addView(col, lp(0, WRAP, 1f))
                        hb.addView(chip("Signal: " + strengths[i], strengthColors[i]))
                        row.addView(hb)
                        row.isClickable = true
                        row.setOnClickListener { sel = i; render(1) }
                        area.addView(row, lp(MATCH, WRAP, 0f, 0, 0, 0, 10))
                    }
                    area.addView(space(6))
                    area.addView(button("Connect") { render(2) })
                    area.addView(space(8))
                    area.addView(button("Rescan", false) { render(0) })
                    area.addView(space(12))
                    area.addView(tv("Having trouble? Check that the pod is switched on and charged.", 12f, Pal.MUTED, false, Gravity.CENTER))
                }
                2 -> {
                    val name = names[sel]
                    val c = card()
                    c.addView(tv("Pairing with SweatIQ Pod " + name, 16f, Pal.TEXT, true))
                    val steps = listOf("Pairing", "Checking firmware", "Starting Na+, K+ and Cl- channels")
                    val marks = ArrayList<TextView>()
                    for (st 
