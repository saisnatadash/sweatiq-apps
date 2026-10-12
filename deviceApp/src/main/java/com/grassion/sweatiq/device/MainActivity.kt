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
                    for (st in steps) {
                        val r = hbox()
                        val m = tv("...", 13f, Pal.MUTED, true)
                        r.addView(tv(st, 13f, Pal.TEXT), lp(0, WRAP, 1f))
                        r.addView(m)
                        marks.add(m)
                        c.addView(r, lp(MATCH, WRAP, 0f, 0, 14, 0, 0))
                    }
                    area.addView(c)
                    for (i in steps.indices) {
                        h.postDelayed({ marks[i].text = "Done"; marks[i].setTextColor(Pal.OK) }, (i * 800 + 700).toLong())
                    }
                    h.postDelayed({
                        podConnected = true
                        podName = name
                        render(3)
                    }, 2600)
                }
                else -> {
                    val c = card()
                    val top = hbox()
                    val tick = tv("OK", 14f, Pal.OK, true, Gravity.CENTER)
                    tick.background = bg(Color.argb(30, 52, 211, 153), 26, Pal.OK, 2)
                    top.addView(tick, lp(dp(52), dp(52), 0f, 0, 0, 14, 0))
                    val col = vbox()
                    col.addView(tv("Device Ready", 22f, Pal.OK, true))
                    col.addView(tv("SweatIQ Pod " + podName + " is connected", 12f, Pal.MUTED), lp(MATCH, WRAP, 0f, 0, 4, 0, 0))
                    top.addView(col, lp(0, WRAP, 1f))
                    c.addView(top)
                    c.addView(space(14))
                    fun kv(k: String, v: String, vc: Int) {
                        val r = hbox()
                        r.addView(tv(k, 13f, Pal.MUTED), lp(0, WRAP, 1f))
                        r.addView(tv(v, 13f, vc, true))
                        c.addView(r, lp(MATCH, WRAP, 0f, 0, 0, 0, 8))
                    }
                    kv("Status", "Connected", Pal.OK)
                    kv("Signal", "Stable", Pal.OK)
                    kv("Battery", "87%", Pal.OK)
                    kv("Firmware", "1.0.3", Pal.TEXT)
                    kv("Sensor channels", "Initialised", Pal.OK)
                    val chips = hbox()
                    chips.addView(chip("Na+ Ready", Pal.NA), lp(WRAP, WRAP, 0f, 0, 0, 6, 0))
                    chips.addView(chip("K+ Ready", Pal.K), lp(WRAP, WRAP, 0f, 0, 0, 6, 0))
                    chips.addView(chip("Cl- Ready", Pal.CL), lp(WRAP, WRAP, 0f, 0, 0, 6, 0))
                    chips.addView(chip("DEMO LINK", Pal.WARN))
                    c.addView(chips, lp(MATCH, WRAP, 0f, 0, 4, 0, 0))
                    area.addView(c)
                    area.addView(space(14))
                    if (forSession) {
                        val chan = chip(if (relayUp) "DATA CHANNEL - CONNECTED" else "DATA CHANNEL - CONNECTING", Pal.OK)
                        relayChip = chan
                        area.addView(chan, lp(WRAP, WRAP))
                        area.addView(space(18))
                        area.addView(button("Continue") { showInit() })
                    } else {
                        area.addView(button("Done") { showHome() })
                    }
                    area.addView(space(8))
                    area.addView(button("Disconnect pod", false, Pal.BAD) {
                        podConnected = false
                        render(0)
                    })
                }
            }
        }

        if (podConnected) render(3) else render(0)
    }

    // ------------------------------------------------------------------ 5. sensor initialisation

    private fun showInit() {
        back = null
        relayChip = null
        val p = page()
        p.addView(header("Initialising Sensors", "Calibrating channels - please wait"))

        class Row(val name: String, val sym: String, val color: Int)
        val rows = listOf(
            Row("Sodium (Na+)", "Na", Pal.NA), Row("Potassium (K+)", "K", Pal.K),
            Row("Chloride (Cl-)", "Cl", Pal.CL), Row("Sweat-rate flow", "~", Pal.RATE)
        )
        val states = ArrayList<TextView>()
        for (r in rows) {
            val c = card(12)
            val hb = hbox()
            val badge = tv(r.sym, 14f, r.color, true, Gravity.CENTER)
            badge.background = bg(Color.argb(30, Color.red(r.color), Color.green(r.color), Color.blue(r.color)), 22, r.color, 1)
            hb.addView(badge, lp(dp(44), dp(44), 0f, 0, 0, 12, 0))
            hb.addView(tv(r.name, 15f, Pal.TEXT, true), lp(0, WRAP, 1f))
            val s = tv("Waiting", 12f, Pal.MUTED, true)
            hb.addView(s)
            states.add(s)
            c.addView(hb)
            p.addView(c, lp(MATCH, WRAP, 0f, 0, 0, 0, 10))
        }
        p.addView(space(10))
        val cont = button("Start Live Monitoring") { showLive() }
        cont.alpha = 0.35f
        cont.isEnabled = false
        p.addView(cont)
        show(p)

        for (i in rows.indices) {
            h.postDelayed({ states[i].text = "Stabilising..."; states[i].setTextColor(Pal.WARN) }, (i * 700).toLong())
            h.postDelayed({ states[i].text = "Signal ready"; states[i].setTextColor(Pal.OK) }, (i * 700 + 650).toLong())
        }
        h.postDelayed({ cont.alpha = 1f; cont.isEnabled = true }, (rows.size * 700 + 300).toLong())
    }

    // ------------------------------------------------------------------ 6. live monitoring

    private fun metricCard(sym: String, name: String, unit: String, color: Int): Pair<LinearLayout, MetricCard> {
        val c = card(12)
        val top = hbox()
        val badge = tv(sym, 12f, color, true, Gravity.CENTER)
        badge.background = bg(Color.argb(30, Color.red(color), Color.green(color), Color.blue(color)), 16, color, 1)
        top.addView(badge, lp(dp(32), dp(32), 0f, 0, 0, 8, 0))
        top.addView(tv(name, 13f, Pal.MUTED, true))
        c.addView(top)
        val v = tv("--", 30f, Pal.TEXT, true)
        c.addView(v, lp(MATCH, WRAP, 0f, 0, 10, 0, 0))
        val bottom = hbox()
        bottom.addView(tv(unit, 11f, Pal.MUTED), lp(0, WRAP, 1f))
        val d = tv("", 11f, Pal.MUTED, true)
        bottom.addView(d)
        c.addView(bottom, lp(MATCH, WRAP, 0f, 0, 2, 0, 6))
        val sp = LineChartView(this)
        sp.showGrid = false
        sp.fillUnder = true
        sp.strokeDp = 1.6f
        c.addView(sp, lp(MATCH, dp(34)))
        return Pair(c, MetricCard(v, d, sp))
    }

    private fun showLive() {
        back = { Toast.makeText(this, "Use End Session to finish", Toast.LENGTH_SHORT).show() }
        startTs = System.currentTimeMillis()
        liveActive = true
        publishState("WAITING")
        val outer = vbox()
        outer.setPadding(dp(16), dp(40), dp(16), dp(12))

        // banner
        val banner = hbox()
        banner.background = bg(Pal.SURFACE, 14, Pal.STROKE, 1)
        banner.setPadding(dp(14), dp(12), dp(14), dp(12))
        val dot = View(this)
        dot.background = bg(Pal.WARN, 6)
        banner.addView(dot, lp(dp(10), dp(10), 0f, 0, 0, 10, 0))
        val bt = tv("WAITING FOR DATA", 12f, Pal.WARN, true)
        banner.addView(bt, lp(0, WRAP, 1f))
        val tm = tv("00:00:00", 14f, Pal.TEXT, true)
        banner.addView(tm)
        bannerDot = dot
        bannerText = bt
        timerTv = tm
        outer.addView(banner)
        outer.addView(space(10))

        // tabs
        val content = FrameLayout(this)
        val live = buildLiveTab()
        val trends = buildTrendsTab()
        val table = buildTableTab()
        content.addView(live)
        content.addView(trends)
        content.addView(table)
        trends.visibility = View.GONE
        table.visibility = View.GONE
        val tabs = listOf("Live", "Trends", "Readings")
        outer.addView(pills(tabs, 0) { i ->
            live.visibility = if (i == 0) View.VISIBLE else View.GONE
            trends.visibility = if (i == 1) View.VISIBLE else View.GONE
            table.visibility = if (i == 2) View.VISIBLE else View.GONE
        })
        outer.addView(space(10))
        outer.addView(content, lp(MATCH, 0, 1f))

        // footer
        outer.addView(space(8))
        val lu = tv("Last update: --", 11f, Pal.MUTED, false, Gravity.CENTER)
        lastUpdateTv = lu
        outer.addView(lu, lp(MATCH, WRAP, 0f, 0, 0, 0, 8))
        outer.addView(button("End Session", false, Pal.BAD) { endSession(false) })
        show(outer, false)
        h.removeCallbacks(tick)
        h.post(tick)
        refreshBanner()
        for (r in readings) paintReading(r, false)
    }

    private fun buildLiveTab(): View {
        val sv = ScrollView(this)
        val l = vbox()
        val r1 = hbox()
        val r2 = hbox()
        val na = metricCard("Na", "Sodium", "mmol/L", Pal.NA)
        val k = metricCard("K", "Potassium", "mmol/L", Pal.K)
        val cl = metricCard("Cl", "Chloride", "mmol/L", Pal.CL)
        val rt = metricCard("~", "Sweat rate", "L/hour", Pal.RATE)
        cardNa = na.second; cardK = k.second; cardCl = cl.second; cardRate = rt.second
        r1.addView(na.first, lp(0, WRAP, 1f, 0, 0, 5, 0))
        r1.addView(k.first, lp(0, WRAP, 1f, 5, 0, 0, 0))
        r2.addView(cl.first, lp(0, WRAP, 1f, 0, 0, 5, 0))
        r2.addView(rt.first, lp(0, WRAP, 1f, 5, 0, 0, 0))
        l.addView(r1)
        l.addView(space(10))
        l.addView(r2)
        l.addView(space(10))

        val tc = card(12)
        val th = hbox()
        th.addView(tv("Live trends", 14f, Pal.TEXT, true), lp(0, WRAP, 1f))
        th.addView(tv("each line auto-scaled", 10f, Pal.MUTED))
        tc.addView(th)
        val ch = LineChartView(this)
        ch.normalizeEach = true
        liveChart = ch
        tc.addView(ch, lp(MATCH, dp(150), 0f, 0, 10, 0, 8))
        val lg = hbox()
        lg.addView(tv("Na+", 11f, Pal.NA, true), lp(0, WRAP, 1f))
        lg.addView(tv("K+", 11f, Pal.K, true), lp(0, WRAP, 1f))
        lg.addView(tv("Cl-", 11f, Pal.CL, true), lp(0, WRAP, 1f))
        lg.addView(tv("Sweat rate", 11f, Pal.RATE, true), lp(0, WRAP, 1f))
        tc.addView(lg)
        l.addView(tc)
        l.addView(space(10))

        val chips = hbox()
        chips.addView(chip("Pod " + podName, Pal.ACCENT), lp(WRAP, WRAP, 0f, 0, 0, 6, 0))
        chips.addView(chip("Battery 87%", Pal.OK), lp(WRAP, WRAP, 0f, 0, 0, 6, 0))
        val sk = chip("Skin --", Pal.MUTED)
        skinChip = sk
        chips.addView(sk)
        l.addView(chips)
        sv.addView(l)
        return sv
    }

    private fun trendCard(title: String, color: Int): Pair<LinearLayout, LineChartView> {
        val c = card(12)
        c.addView(tv(title, 13f, color, true))
        val ch = LineChartView(this)
        ch.fillUnder = true
        c.addView(ch, lp(MATCH, dp(110), 0f, 0, 8, 0, 0))
        return Pair(c, ch)
    }

    private fun buildTrendsTab(): View {
        val sv = ScrollView(this)
        val l = vbox()
        val a = trendCard("Sodium (Na+) mmol/L", Pal.NA)
        val b = trendCard("Potassium (K+) mmol/L", Pal.K)
        val c = trendCard("Chloride (Cl-) mmol/L", Pal.CL)
        val d = trendCard("Sweat rate L/hour", Pal.RATE)
        trendNa = a.second; trendK = b.second; trendCl = c.second; trendRate = d.second
        for (x in listOf(a, b, c, d)) l.addView(x.first, lp(MATCH, WRAP, 0f, 0, 0, 0, 10))
        sv.addView(l)
        return sv
    }

    private fun tableRow(cells: List<String>, header: Boolean, colors: List<Int>? = null): LinearLayout {
        val r = hbox()
        r.setPadding(dp(8), dp(7), dp(8), dp(7))
        if (header) r.setBackgroundColor(Pal.SURFACE2)
        for ((i, c) in cells.withIndex()) {
            val col = if (header) Pal.MUTED else (colors?.getOrNull(i) ?: Pal.TEXT)
            r.addView(tv(c, if (header) 10.5f else 12f, col, header), lp(0, WRAP, if (i == 0) 1.5f else 1f))
        }
        return r
    }

    private fun buildTableTab(): View {
        val sv = ScrollView(this)
        val l = vbox()
        val c = card(4)
        c.addView(tableRow(listOf("Time", "Na+", "K+", "Cl-", "Rate"), true))
        val box = vbox()
        rowsBox = box
        c.addView(box)
        l.addView(c)
        sv.addView(l)
        return sv
    }

    private fun refreshBanner() {
        val t = bannerText ?: return
        val d = bannerDot ?: return
        val (txt, col) = when {
            ended -> Pair("SESSION ENDED", Pal.MUTED)
            paused -> Pair("STREAM PAUSED", Pal.WARN)
            !relayUp && readings.isNotEmpty() -> Pair("RECONNECTING...", Pal.BAD)
            readings.isEmpty() -> Pair("WAITING FOR DATA", Pal.WARN)
            else -> Pair("LIVE SESSION", Pal.OK)
        }
        t.text = txt
        t.setTextColor(col)
        d.background = bg(col, 6)
    }

    // ------------------------------------------------------------------ incoming messages

    private fun onRemote(kind: String, payload: String) {
        if (ended) return
        when (kind) {
            "reading" -> {
                if (payload.isEmpty()) return
                val r0 = Reading.parse(payload) ?: return
                if (readings.isNotEmpty() && r0.seq == lastSeq && r0.ts == lastTs) return
                if (lastSeq > 0 && r0.seq > lastSeq + 1) missing += r0.seq - lastSeq - 1
                lastSeq = r0.seq
                lastTs = r0.ts
                // use this phone's own clock so the timer never depends on the other phone's time
                val r = r0.copy(ts = System.currentTimeMillis())
                if (readings.isEmpty()) {
                    firstReadingTs = r.ts
                    publishState("LIVE")
                }
                if (paused) resumeInternal()
                readings.add(r)
                if (liveActive) paintReading(r, true)
            }
            "cmd" -> when (payload.trim().uppercase()) {
                "PAUSE" -> {
                    if (!paused && liveActive) {
                        paused = true
                        pauseStart = System.currentTimeMillis()
                        publishState("PAUSED")
                        refreshBanner()
                    }
                }
                "RESUME", "START" -> resumeInternal()
                "END" -> if (liveActive) endSession(true)
            }
            "hello" -> {
                operatorLinked = true
                refreshCodeChips()
                publishState(if (liveActive) (if (paused) "PAUSED" else (if (readings.isEmpty()) "WAITING" else "LIVE")) else "WAITING")
            }
        }
    }

    private fun resumeInternal() {
        if (paused) {
            pausedAccum += System.currentTimeMillis() - pauseStart
            paused = false
            if (readings.isNotEmpty()) publishState("LIVE")
            refreshBanner()
        }
    }

    private fun paintReading(r: Reading, animate: Boolean) {
        val n = readings.size
        val prev = if (n >= 2) readings[n - 2] else null
        fun upd(card: MetricCard?, v: Double, pv: Double?, two: Boolean, series: List<Double>, color: Int) {
            val c = card ?: return
            c.value.text = if (two) f2(v) else f1(v)
            if (pv != null) {
                val d = v - pv
                val sym = if (d > 0.0001) "up " else if (d < -0.0001) "down " else ""
                c.delta.text = sym + (if (two) f2(Math.abs(d)) else f1(Math.abs(d)))
                c.delta.setTextColor(if (d > 0.0001) Pal.OK else if (d < -0.0001) Pal.BAD else Pal.MUTED)
            }
            c.spark.set(listOf(series.takeLast(40).map { it.toFloat() }), listOf(color))
        }
        val last = readings.takeLast(60)
        val sNa = last.map { it.na }
        val sK = last.map { it.k }
        val sCl = last.map { it.cl }
        val sRt = last.map { it.rate }
        upd(cardNa, r.na, prev?.na, false, sNa, Pal.NA)
        upd(cardK, r.k, prev?.k, true, sK, Pal.K)
        upd(cardCl, r.cl, prev?.cl, false, sCl, Pal.CL)
        upd(cardRate, r.rate, prev?.rate, true, sRt, Pal.RATE)
        liveChart?.set(
            listOf(sNa.map { it.toFloat() }, sK.map { it.toFloat() }, sCl.map { it.toFloat() }, sRt.map { it.toFloat() }),
            listOf(Pal.NA, Pal.K, Pal.CL, Pal.RATE)
        )
        trendNa?.set(listOf(sNa.map { it.toFloat() }), listOf(Pal.NA))
        trendK?.set(listOf(sK.map { it.toFloat() }), listOf(Pal.K))
        trendCl?.set(listOf(sCl.map { it.toFloat() }), listOf(Pal.CL))
        trendRate?.set(listOf(sRt.map { it.toFloat() }), listOf(Pal.RATE))
        skinChip?.text = "Skin " + f1(r.skinT) + " C"
        lastUpdateTv?.text = "Last update: " + clock(r.ts)
        val box = rowsBox
        if (box != null) {
            box.addView(tableRow(listOf(clock(r.ts), f1(r.na), f2(r.k), f1(r.cl), f2(r.rate)), false,
                listOf(Pal.TEXT, Pal.NA, Pal.K, Pal.CL, Pal.RATE)), 0)
            while (box.childCount > 40) box.removeViewAt(box.childCount - 1)
        }
        refreshBanner()
    }

    // ------------------------------------------------------------------ 7. end + report

    private fun endSession(remote: Boolean) {
        if (ended) return
        val endTs = System.currentTimeMillis()
        val d = if (readings.isEmpty()) 0L else {
            if (paused) resumeInternal()
            (endTs - firstReadingTs - pausedAccum).coerceAtLeast(0)
        }
        ended = true
        liveActive = false
        h.removeCallbacks(tick)
        publishState("ENDED")
        val s = SessionSummary.build(
            "SQ-" + code, athleteId, athleteName, sport,
            if (firstReadingTs > 0) firstReadingTs else startTs, endTs, d, drops + missing, readings
        )
        saveHistory(s)
        val copy = ArrayList(readings)
        showReport(s, copy)
        if (remote) Toast.makeText(this, "Session ended", Toast.LENGTH_SHORT).show()
    }

    private fun showReport(s: SessionSummary, list: List<Reading>?) {
        back = { showHome() }
        val p = page()
        p.addView(header("Session Report", s.athleteName + " (" + s.athleteId + ") - " + s.sport))
        val warn = tv("SIMULATED DATA - NOT A BIOSENSOR MEASUREMENT", 11f, Pal.WARN, true, Gravity.CENTER)
        warn.background = bg(Color.argb(30, 251, 191, 36), 12, Color.argb(90, 251, 191, 36), 1)
        warn.setPadding(dp(10), dp(10), dp(10), dp(10))
        p.addView(warn)
        p.addView(space(14))

        val grid = hbox()
        fun stat(label: String, value: String): LinearLayout {
            val c = card(12)
            c.addView(fieldLabel(label))
            c.addView(tv(value, 20f, Pal.TEXT, true), lp(MATCH, WRAP, 0f, 0, 6, 0, 0))
            return c
        }
        grid.addView(stat("Duration", dur(s.durationMs)), lp(0, WRAP, 1f, 0, 0, 5, 0))
        grid.addView(stat("Readings", s.count.toString()), lp(0, WRAP, 1f, 5, 0, 5, 0))
        grid.addView(stat("Interruptions", s.interruptions.toString()), lp(0, WRAP, 1f, 5, 0, 0, 0))
        p.addView(grid)
        p.addView(space(12))

        val tc = card(4)
        tc.addView(tableRow(listOf("Metric", "Average", "Min", "Max"), true))
        fun mrow(name: String, m: MetricStat, two: Boolean, color: Int) {
            fun f(d: Double) = if (two) f2(d) else f1(d)
            tc.addView(tableRow(listOf(name, f(m.avg), f(m.min), f(m.max)), false, listOf(color, Pal.TEXT, Pal.TEXT, Pal.TEXT)))
        }
        mrow("Sodium Na+", s.na, false, Pal.NA)
        mrow("Potassium K+", s.k, true, Pal.K)
        mrow("Chloride Cl-", s.cl, false, Pal.CL)
        mrow("Sweat rate L/h", s.rate, true, Pal.RATE)
        p.addView(tc)
        p.addView(space(8))
        p.addView(tv("Started " + dateTime(s.startTs), 11f, Pal.MUTED))
        p.addView(space(20))

        p.addView(button("Export PDF report") {
            try {
                val f = ReportPdf.build(this, s, list ?: emptyList())
                ReportPdf.share(this, f)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not create report", Toast.LENGTH_LONG).show()
            }
        })
        p.addView(space(10))
        p.addView(button("Back to Home", false) { showHome() })
        show(p)
    }

    // ------------------------------------------------------------------ 8. history

    private fun showHistory() {
        back = { showHome() }
        val p = page()
        p.addView(header("Session History", "Reports saved on this phone"))
        val hs = history()
        if (hs.isEmpty()) {
            p.addView(tv("No sessions yet.", 14f, Pal.MUTED))
        }
        for (s in hs) {
            val c = card(14)
            c.addView(tv(s.athleteName + " - " + s.sport, 16f, Pal.TEXT, true))
            c.addView(tv(dateTime(s.startTs) + "  |  " + dur(s.durationMs) + "  |  " + s.count + " readings", 12f, Pal.MUTED),
                lp(MATCH, WRAP, 0f, 0, 4, 0, 0))
            c.isClickable = true
            c.setOnClickListener { showReport(s, null) }
            p.addView(c, lp(MATCH, WRAP, 0f, 0, 0, 0, 10))
        }
        p.addView(space(10))
        p.addView(button("Back", false) { showHome() })
        show(p)
    }
}
