package com.grassion.sweatiq.control

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.grassion.sweatiq.common.*
import org.json.JSONObject

/**
 * SweatIQ Control App - the operator phone that stays out of sight.
 * It sends simulated sensor readings (manual or automatic) and session commands to the Device app.
 */
class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private val h = Handler(Looper.getMainLooper())
    private var back: (() -> Unit)? = null

    // ----- session / link -----
    private var code = ""
    private var operatorId = "OP-01"
    private var link: Link? = null
    private var relayUp = false
    private var relayLabel = "connecting"
    private var sessionState = "UNKNOWN"
    private var athleteName = ""
    private var athleteId = ""
    private var joinedAt = 0L

    // ----- simulation -----
    private val engine = SimEngine()
    private var seq = 0
    private var sent = 0
    private var lastSentTs = 0L
    private var autoRunning = false
    private var autoPaused = false
    private var intervalMs = 2000L
    private var selected: Scenario = Scenario.NORMAL
    private val presets = listOf(Scenario.NORMAL, Scenario.HIGH, Scenario.HEAT, Scenario.RECOVERY, Scenario.INTERVAL)

    private class LogRow(val r: Reading, var ok: Boolean?)
    private val log = ArrayList<LogRow>()
    private val previewSeries = ArrayList<Reading>()

    // ----- views -----
    private var stateChip: TextView? = null
    private var linkChip: TextView? = null
    private var accessStatus: TextView? = null
    private var accessSession: TextView? = null
    private var accessContinue: TextView? = null
    private var monLink: TextView? = null
    private var monState: TextView? = null
    private var monSent: TextView? = null
    private var monLast: TextView? = null
    private var monUptime: TextView? = null
    private var logBox: LinearLayout? = null
    private var prevVals: TextView? = null
    private var prevChart: LineChartView? = null
    private var autoStatus: TextView? = null
    private val scenarioCards = ArrayList<LinearLayout>()
    private var eNa: EditText? = null
    private var eK: EditText? = null
    private var eCl: EditText? = null
    private var eRate: EditText? = null
    private var eSkin: EditText? = null
    private var eAmb: EditText? = null

    private val autoTick = object : Runnable {
        override fun run() {
            if (autoRunning && !autoPaused) {
                sendNext()
                h.postDelayed(this, intervalMs)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Pal.ACCENT = Color.parseColor("#FF8A3D")
        Pal.ON_ACCENT = Color.parseColor("#2A1200")
        window.statusBarColor = Pal.BG
        window.navigationBarColor = Pal.BG
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this)
        root.setBackgroundColor(Pal.BG)
        setContentView(root)
        showAccess()
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

    private fun prefs() = getSharedPreferences("sweatiq_control", Context.MODE_PRIVATE)

    private fun brand(): LinearLayout {
        val l = hbox()
        l.addView(tv("SWEATIQ", 18f, Pal.TEXT, true))
        l.addView(tv(" CONTROL", 18f, Pal.ACCENT, true))
        return l
    }

    private fun stateColor(s: String): Int = when (s) {
        "LIVE" -> Pal.OK
        "WAITING" -> Pal.WARN
        "PAUSED" -> Pal.WARN
        "ENDED" -> Pal.MUTED
        else -> Pal.MUTED
    }

    // ------------------------------------------------------------------ 1. operator access

    private fun showAccess() {
        back = null
        link?.stop()
        link = null
        relayUp = false
        sessionState = "UNKNOWN"
        autoRunning = false
        h.removeCallbacks(autoTick)
        val p = vbox()
        p.setPadding(dp(20), dp(44), dp(20), dp(24))
        p.addView(brand())
        p.addView(space(24))
        p.addView(tv("Operator Access", 26f, Pal.TEXT, true))
        p.addView(tv("Enter the session code shown on the SweatIQ Device app to start controlling the data feed.", 13f, Pal.MUTED),
            lp(MATCH, WRAP, 0f, 0, 6, 0, 22))

        val codeF = input("6-digit session code", prefs().getString("code", "") ?: "", true)
        codeF.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22f)
        val opF = input("Operator ID", operatorId)
        p.addView(fieldLabel("Session code")); p.addView(codeF, lp(MATCH, WRAP, 0f, 0, 6, 0, 14))
        p.addView(fieldLabel("Operator ID")); p.addView(opF, lp(MATCH, WRAP, 0f, 0, 6, 0, 14))
        p.addView(fieldLabel("Role"))
        val role = tv("Simulation Operator", 15f, Pal.TEXT, true)
        role.background = bg(Pal.SURFACE2, 12, Pal.STROKE, 1)
        role.setPadding(dp(14), dp(13), dp(14), dp(13))
        p.addView(role, lp(MATCH, WRAP, 0f, 0, 6, 0, 22))

        p.addView(button("Connect to Session") {
            val c = codeF.text.toString().trim()
            if (c.length < 4) {
                Toast.makeText(this, "Enter the session code from the Device app", Toast.LENGTH_SHORT).show()
            } else {
                code = c
                operatorId = opF.text.toString().trim().ifEmpty { "OP-01" }
                prefs().edit().putString("code", code).apply()
                showConnecting()
            }
        })
        p.addView(space(18))
        p.addView(tv("This app only sends simulated values. It is not connected to any sensor hardware.", 11f, Pal.MUTED, false, Gravity.CENTER))
        show(p)
    }

    // ------------------------------------------------------------------ 2. connecting / confirm session

    private fun showConnecting() {
        back = { showAccess() }
        startLink()
        val p = vbox()
        p.setPadding(dp(20), dp(44), dp(20), dp(24))
        p.addView(brand())
        p.addView(space(24))
        p.addView(tv("Joining session", 26f, Pal.TEXT, true))
        p.addView(tv("Session " + code, 13f, Pal.MUTED), lp(MATCH, WRAP, 0f, 0, 6, 0, 20))

        val c = card()
        val s1 = tv("Connecting to data channel...", 14f, Pal.WARN, true)
        accessStatus = s1
        c.addView(fieldLabel("Connection status"))
        c.addView(s1, lp(MATCH, WRAP, 0f, 0, 6, 0, 14))
        val s2 = tv("Looking for the Device app...", 14f, Pal.MUTED, true)
        accessSession = s2
        c.addView(fieldLabel("Target Device app"))
        c.addView(s2, lp(MATCH, WRAP, 0f, 0, 6, 0, 0))
        p.addView(c)
        p.addView(space(20))

        val cont = button("Open Control Panel") { showPanel(0) }
        cont.alpha = 0.35f
        cont.isEnabled = false
        accessContinue = cont
        p.addView(cont)
        p.addView(space(10))
        p.addView(button("Change session code", false) { showAccess() })
        show(p)
        refreshAccess()
    }

    private fun refreshAccess() {
        accessStatus?.let {
            it.text = if (relayUp) "Connected - " + relayLabel else "Connecting to data channel..."
            it.setTextColor(if (relayUp) Pal.OK else Pal.WARN)
        }
        accessSession?.let {
            if (sessionState == "UNKNOWN") {
                it.text = if (relayUp) "Waiting for Device app to announce session..." else "Looking for the Device app..."
                it.setTextColor(Pal.MUTED)
            } else {
                it.text = athleteName + " (" + athleteId + ") - " + sessionState
                it.setTextColor(stateColor(sessionState))
            }
        }
        accessContinue?.let {
            it.alpha = if (relayUp) 1f else 0.35f
            it.isEnabled = relayUp
        }
    }

    // ------------------------------------------------------------------ link

    private fun startLink() {
        link?.stop()
        relayUp = false
        sessionState = "UNKNOWN"
        joinedAt = System.currentTimeMillis()
        val l = Link(code, "control", listOf("state"),
            { up, label -> onRelay(up, label) },
            { kind, payload -> onRemote(kind, payload) })
        link = l
        l.start()
    }

    private fun onRelay(up: Boolean, label: String) {
        relayUp = up
        relayLabel = label
        if (up) link?.send("hello", operatorId)
        refreshAccess()
        refreshHeader()
        refreshMonitor()
    }

    private fun onRemote(kind: String, payload: String) {
        if (kind != "state") return
        try {
            val o = JSONObject(payload)
            sessionState = o.optString("state", "UNKNOWN")
            athleteName = o.optString("athleteName", "")
            athleteId = o.optString("athleteId", "")
        } catch (e: Exception) {
            return
        }
        if (sessionState == "ENDED") {
            autoRunning = false
            h.removeCallbacks(autoTick)
            refreshAuto()
        }
        refreshAccess()
        refreshHeader()
        refreshMonitor()
    }

    private fun command(cmd: String) {
        link?.send("cmd", cmd, false) { ok ->
            if (!ok) Toast.makeText(this, "Not sent - no data channel", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------ sending readings

    private fun sendReading(base: Reading) {
        seq++
        val r = base.copy(seq = seq, ts = System.currentTimeMillis())
        val row = LogRow(r, null)
        log.add(row)
        while (log.size > 60) log.removeAt(0)
        previewSeries.add(r)
        while (previewSeries.size > 60) previewSeries.removeAt(0)
        engine.setCurrent(r.na, r.k, r.cl, r.rate)
        link?.send("reading", r.toJson(), true) { ok ->
            row.ok = ok
            if (ok) {
                sent++
                lastSentTs = r.ts
            }
            refreshMonitor()
        }
        refreshPreview()
        refreshMonitor()
    }

    private fun sendNext() {
        sendReading(engine.next())
    }

    // ------------------------------------------------------------------ panel

    private fun showPanel(tab: Int) {
        back = { showAccess() }
        scenarioCards.clear()
        val outer = vbox()
        outer.setPadding(dp(14), dp(36), dp(14), dp(8))

        val top = hbox()
        top.addView(brand(), lp(0, WRAP, 1f))
        val lc = chip("--", Pal.MUTED)
        linkChip = lc
        top.addView(lc)
        outer.addView(top)
        outer.addView(space(8))
        val info = hbox()
        info.addView(tv("Session " + code + "  |  " + operatorId, 12f, Pal.MUTED, true), lp(0, WRAP, 1f))
        val sc = chip("--", Pal.MUTED)
        stateChip = sc
        info.addView(sc)
        outer.addView(info)
        outer.addView(space(10))

        val content = FrameLayout(this)
        val tabs = listOf(buildManual(), buildAuto(), buildEvents(), buildMonitor())
        for (t in tabs) content.addView(t)
        outer.addView(content, lp(MATCH, 0, 1f))

        // bottom tab bar
        val bar = hbox()
        bar.background = bg(Pal.SURFACE, 16, Pal.STROKE, 1)
        val names = listOf("Manual", "Auto", "Events", "Monitor")
        val labels = ArrayList<TextView>()
        fun select(i: Int) {
            for (j in tabs.indices) tabs[j].visibility = if (i == j) View.VISIBLE else View.GONE
            for (j in labels.indices) {
                labels[j].setTextColor(if (i == j) Pal.ACCENT else Pal.MUTED)
                labels[j].background = if (i == j) bg(Color.argb(30, 255, 138, 61), 12) else null
            }
        }
        for ((i, n) in names.withIndex()) {
            val t = tv(n, 13f, Pal.MUTED, true, Gravity.CENTER)
            t.setPadding(0, dp(12), 0, dp(12))
            t.setOnClickListener { select(i) }
            bar.addView(t, lp(0, WRAP, 1f, 4, 4, 4, 4))
            labels.add(t)
        }
        outer.addView(space(8))
        outer.addView(bar)
        show(outer, false)
        select(tab)
        refreshHeader()
        refreshPreview()
        refreshMonitor()
        refreshAuto()
    }

    private fun refreshHeader() {
        linkChip?.let {
            it.text = if (relayUp) "LINK UP" else "LINK DOWN"
            it.setTextColor(if (relayUp) Pal.OK else Pal.BAD)
        }
        stateChip?.let {
            it.text = if (sessionState == "UNKNOWN") "SESSION: --" else athleteName.ifEmpty { "Athlete" } + " - " + sessionState
            it.setTextColor(stateColor(sessionState))
        }
    }

    // ----- Manual tab

    private fun stepper(label: String, unit: String, color: Int, start: Double, step: Double, two: Boolean): Pair<LinearLayout, EditText> {
        fun fmt(d: Double) = if (two) f2(d) else f1(d)
        val c = card(12)
        val head = hbox()
        head.addView(tv(label, 14f, color, true), lp(0, WRAP, 1f))
        head.addView(tv(unit, 11f, Pal.MUTED))
        c.addView(head)
        val row = hbox()
        val e = input("0", fmt(start), true)
        e.gravity = Gravity.CENTER
        e.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22f)
        val minus = button("-", false, color) {
            val v = e.text.toString().toDoubleOrNull() ?: start
            e.setText(fmt((v - step).coerceAtLeast(0.0)))
        }
        val plus = button("+", false, color) {
            val v = e.text.toString().toDoubleOrNull() ?: start
            e.setText(fmt(v + step))
        }
        row.addView(minus, lp(dp(56), WRAP))
        row.addView(e, lp(0, WRAP, 1f, 8, 0, 8, 0))
        row.addView(plus, lp(dp(56), WRAP))
        c.addView(row, lp(MATCH, WRAP, 0f, 0, 10, 0, 0))
        return Pair(c, e)
    }

    private fun buildManual(): View {
        val sv = ScrollView(this)
        val l = vbox()
        l.addView(tv("Manual control", 20f, Pal.TEXT, true))
        l.addView(tv("Set exact values and send them to the Device app.", 12f, Pal.MUTED), lp(MATCH, WRAP, 0f, 0, 4, 0, 12))
        val a = stepper("Sodium (Na+)", "mmol/L", Pal.NA, engine.na, 1.0, false)
        val b = stepper("Potassium (K+)", "mmol/L", Pal.K, engine.k, 0.1, true)
        val c = stepper("Chloride (Cl-)", "mmol/L", Pal.CL, engine.cl, 1.0, false)
        val d = stepper("Sweat rate", "L/hour", Pal.RATE, engine.rate, 0.05, true)
        eNa = a.second; eK = b.second; eCl = c.second; eRate = d.second
        for (x in listOf(a, b, c, d)) l.addView(x.first, lp(MATCH, WRAP, 0f, 0, 0, 0, 10))

        val extra = card(12)
        extra.addView(tv("Optional parameters", 13f, Pal.TEXT, true))
        val r = hbox()
        val sk = input("Skin C", "32.5", true)
        val am = input("Ambient C", "28.0", true)
        eSkin = sk; eAmb = am
        val c1 = vbox(); c1.addView(fieldLabel("Skin temp C")); c1.addView(sk, lp(MATCH, WRAP, 0f, 0, 4, 0, 0))
        val c2 = vbox(); c2.addView(fieldLabel("Ambient C")); c2.addView(am, lp(MATCH, WRAP, 0f, 0, 4, 0, 0))
        r.addView(c1, lp(0, WRAP, 1f, 0, 0, 6, 0))
        r.addView(c2, lp(0, WRAP, 1f, 6, 0, 0, 0))
        extra.addView(r, lp(MATCH, WRAP, 0f, 0, 10, 0, 0))
        l.addView(extra)
        l.addView(space(14))
        l.addView(button("Send Reading") {
            val na = eNa?.text.toString().toDoubleOrNull()
            val k = eK?.text.toString().toDoubleOrNull()
            val cl = eCl?.text.toString().toDoubleOrNull()
            val rt = eRate?.text.toString().toDoubleOrNull()
            if (na == null || k == null || cl == null || rt == null) {
                Toast.makeText(this, "Enter all four values", Toast.LENGTH_SHORT).show()
            } else {
                val skin = eSkin?.text.toString().toDoubleOrNull() ?: 32.5
                val amb = eAmb?.text.toString().toDoubleOrNull() ?: 28.0
                sendReading(Reading(0, 0, na, k, cl, rt, skin, amb, "Manual"))
                Toast.makeText(this, "Reading sent", Toast.LENGTH_SHORT).show()
            }
        })
        l.addView(space(20))
        sv.addView(l)
        return sv
    }

    // ----- Auto tab

    private fun buildAuto(): View {
        val sv = ScrollView(this)
        val l = vbox()
        l.addView(tv("Automatic simulation", 20f, Pal.TEXT, true))
        l.addView(tv("Streams changing values at a fixed interval.", 12f, Pal.MUTED), lp(MATCH, WRAP, 0f, 0, 4, 0, 12))

        l.addView(fieldLabel("Scenario"))
        l.addView(space(6))
        for (s in presets) {
            val c = vbox()
            c.setPadding(dp(14), dp(11), dp(14), dp(11))
            c.addView(tv(s.label, 14f, Pal.TEXT, true))
            c.addView(tv(s.desc, 11f, Pal.MUTED), lp(MATCH, WRAP, 0f, 0, 2, 0, 0))
            c.setOnClickListener {
                selected = s
                engine.setScenario(s)
                paintScenarios()
            }
            scenarioCards.add(c)
            l.addView(c, lp(MATCH, WRAP, 0f, 0, 0, 0, 8))
        }
        paintScenarios()

        l.addView(space(6))
        l.addView(fieldLabel("Update interval"))
        val ivs = listOf("1 s", "2 s", "3 s", "5 s")
        val ivMs = listOf(1000L, 2000L, 3000L, 5000L)
        l.addView(pills(ivs, ivMs.indexOf(intervalMs).coerceAtLeast(1)) { intervalMs = ivMs[it] }, lp(MATCH, WRAP, 0f, 0, 8, 0, 12))

        val vr = hbox()
        vr.addView(tv("Controlled variations", 14f, Pal.TEXT, true), lp(0, WRAP, 1f))
        val sw = Switch(this)
        sw.isChecked = engine.variation
        sw.setOnCheckedChangeListener { _, on -> engine.variation = on }
        vr.addView(sw)
        l.addView(vr, lp(MATCH, WRAP, 0f, 0, 0, 0, 14))

        val pv = card(12)
        val pvh = hbox()
        pvh.addView(tv("Live preview (sent values)", 13f, Pal.TEXT, true), lp(0, WRAP, 1f))
        val st = tv("STOPPED", 11f, Pal.MUTED, true)
        autoStatus = st
        pvh.addView(st)
        pv.addView(pvh)
        val vals = tv("--", 13f, Pal.TEXT, true)
        prevVals = vals
        pv.addView(vals, lp(MATCH, WRAP, 0f, 0, 8, 0, 6))
        val ch = LineChartView(this)
        ch.normalizeEach = true
        prevChart = ch
        pv.addView(ch, lp(MATCH, dp(90)))
        l.addView(pv)
        l.addView(space(12))

        val r1 = hbox()
        r1.addView(button("Start") { startAuto() }, lp(0, WRAP, 1f, 0, 0, 5, 0))
        r1.addView(button("Stop", false, Pal.BAD) { stopAuto() }, lp(0, WRAP, 1f, 5, 0, 0, 0))
        l.addView(r1)
        l.addView(space(8))
        val r2 = hbox()
        r2.addView(button("Pause", false) { pauseAuto() }, lp(0, WRAP, 1f, 0, 0, 5, 0))
        r2.addView(button("Resume", false) { resumeAuto() }, lp(0, WRAP, 1f, 5, 0, 0, 0))
        l.addView(r2)
        l.addView(space(20))
        sv.addView(l)
        return sv
    }

    private fun paintScenarios() {
        for ((i, c) in scenarioCards.withIndex()) {
            val on = i < presets.size && presets[i] == selected
            c.background = if (on) bg(Color.argb(40, 255, 138, 61), 14, Pal.ACCENT, 1) else bg(Pal.SURFACE, 14, Pal.STROKE, 1)
        }
    }

    private fun startAuto() {
        if (!relayUp) {
            Toast.makeText(this, "No data channel yet", Toast.LENGTH_SHORT).show()
            return
        }
        engine.setScenario(selected)
        if (sessionState != "LIVE") command("START")
        autoRunning = true
        autoPaused = false
        h.removeCallbacks(autoTick)
        h.post(autoTick)
        refreshAuto()
    }

    private fun stopAuto() {
        autoRunning = false
        autoPaused = false
        h.removeCallbacks(autoTick)
        refreshAuto()
    }

    private fun pauseAuto() {
        if (!autoRunning || autoPaused) return
        autoPaused = true
        h.removeCallbacks(autoTick)
        command("PAUSE")
        refreshAuto()
    }

    private fun resumeAuto() {
        if (!autoRunning || !autoPaused) return
        autoPaused = false
        command("RESUME")
        h.removeCallbacks(autoTick)
        h.post(autoTick)
        refreshAuto()
    }

    private fun refreshAuto() {
        autoStatus?.let {
            when {
                autoRunning && autoPaused -> { it.text = "PAUSED"; it.setTextColor(Pal.WARN) }
                autoRunning -> { it.text = "RUNNING - every " + (intervalMs / 1000) + " s"; it.setTextColor(Pal.OK) }
                else -> { it.text = "STOPPED"; it.setTextColor(Pal.MUTED) }
            }
        }
    }

    private fun refreshPreview() {
        val last = previewSeries.lastOrNull() ?: return
        prevVals?.text = "Na " + f1(last.na) + "   K " + f2(last.k) + "   Cl " + f1(last.cl) + "   Rate " + f2(last.rate)
        prevChart?.set(
            listOf(previewSeries.map { it.na.toFloat() }, previewSeries.map { it.k.toFloat() },
                previewSeries.map { it.cl.toFloat() }, previewSeries.map { it.rate.toFloat() }),
            listOf(Pal.NA, Pal.K, Pal.CL, Pal.RATE)
        )
    }

    // ----- Events tab

    private fun buildEvents(): View {
        val sv = ScrollView(this)
        val l = vbox()
        l.addView(tv("Session events", 20f, Pal.TEXT, true))
        l.addView(tv("Repeatable demo controls.", 12f, Pal.MUTED), lp(MATCH, WRAP, 0f, 0, 4, 0, 12))
        l.addView(button("Start Session") { command("START"); Toast.makeText(this, "Start sent", Toast.LENGTH_SHORT).show() })
        l.addView(space(8))
        l.addView(button("Send New Reading", false) { sendNext() })
        l.addView(space(8))
        l.addView(button("Pause Incoming Data", false, Pal.WARN) {
            autoPaused = true
            h.removeCallbacks(autoTick)
            command("PAUSE")
            refreshAuto()
        })
        l.addView(space(8))
        l.addView(button("Resume Incoming Data", false, Pal.OK) {
            command("RESUME")
            if (autoRunning) {
                autoPaused = false
                h.removeCallbacks(autoTick)
                h.post(autoTick)
            }
            refreshAuto()
        })
        l.addView(space(8))
        l.addView(button("End Session", true, Pal.BAD, Color.WHITE) {
            stopAuto()
            command("END")
        })
        l.addView(space(18))
        l.addView(fieldLabel("Quick triggers"))
        l.addView(space(8))
        val trig = listOf(
            Pair("Resting", Scenario.REST), Pair("Warm Up", Scenario.WARM_UP),
            Pair("High Intensity", Scenario.HIGH), Pair("Cool Down", Scenario.COOL),
            Pair("Hydration", Scenario.RECOVERY), Pair("Dehydration", Scenario.HEAT)
        )
        var i = 0
        while (i < trig.size) {
            val row = hbox()
            for (j in 0..1) {
                val t = trig[i + j]
                row.addView(button(t.first, false) {
                    engine.setScenario(t.second)
                    if (presets.contains(t.second)) { selected = t.second; paintScenarios() }
                    if (!autoRunning) sendNext()
                    Toast.makeText(this, "Trigger: " + t.first, Toast.LENGTH_SHORT).show()
                }, lp(0, WRAP, 1f, if (j == 0) 0 else 4, 0, if (j == 0) 4 else 0, 8))
            }
            l.addView(row)
            i += 2
        }
        l.addView(space(20))
        sv.addView(l)
        return sv
    }

    // ----- Monitor tab

    private fun buildMonitor(): View {
        val sv = ScrollView(this)
        val l = vbox()
        l.addView(tv("Transmission monitor", 20f, Pal.TEXT, true))
        l.addView(tv("What has been sent to the Device app.", 12f, Pal.MUTED), lp(MATCH, WRAP, 0f, 0, 4, 0, 12))
        val c = card(14)
        fun line(label: String): TextView {
            c.addView(fieldLabel(label), lp(MATCH, WRAP, 0f, 0, 8, 0, 0))
            val v = tv("--", 14f, Pal.TEXT, true)
            c.addView(v, lp(MATCH, WRAP, 0f, 0, 3, 0, 0))
            return v
        }
        monLink = line("Data channel")
        monState = line("Session")
        monSent = line("Readings sent")
        monLast = line("Last sent")
        monUptime = line("Time in session")
        l.addView(c)
        l.addView(space(12))
        val lc = card(4)
        val hd = hbox()
        hd.setPadding(dp(8), dp(7), dp(8), dp(7))
        hd.setBackgroundColor(Pal.SURFACE2)
        for ((i, t) in listOf("#", "Time", "Na+", "K+", "Cl-", "Rate", "").withIndex()) {
            hd.addView(tv(t, 10f, Pal.MUTED, true), lp(0, WRAP, if (i == 6) 0.5f else 1f))
        }
        lc.addView(hd)
        val box = vbox()
        logBox = box
        lc.addView(box)
        l.addView(lc)
        l.addView(space(20))
        sv.addView(l)
        return sv
    }

    private fun refreshMonitor() {
        monLink?.let {
            it.text = if (relayUp) "Connected - " + relayLabel else "Reconnecting..."
            it.setTextColor(if (relayUp) Pal.OK else Pal.BAD)
        }
        monState?.let {
            it.text = if (sessionState == "UNKNOWN") "Not announced yet" else athleteName + " - " + sessionState
            it.setTextColor(stateColor(sessionState))
        }
        monSent?.text = sent.toString()
        monLast?.text = if (lastSentTs == 0L) "--" else clock(lastSentTs)
        monUptime?.text = dur(System.currentTimeMillis() - joinedAt)
        val box = logBox ?: return
        box.removeAllViews()
        for (row in log.asReversed().take(30)) {
            val r = row.r
            val hb = hbox()
            hb.setPadding(dp(8), dp(6), dp(8), dp(6))
            val vals = listOf(r.seq.toString(), clock(r.ts), f1(r.na), f2(r.k), f1(r.cl), f2(r.rate))
            val cols = listOf(Pal.MUTED, Pal.TEXT, Pal.NA, Pal.K, Pal.CL, Pal.RATE)
            for (i in vals.indices) hb.addView(tv(vals[i], 11f, cols[i]), lp(0, WRAP, 1f))
            val ok = row.ok
            hb.addView(tv(if (ok == null) "..." else if (ok) "OK" else "X", 11f,
                if (ok == null) Pal.MUTED else if (ok) Pal.OK else Pal.BAD, true), lp(0, WRAP, 0.5f))
            box.addView(hb)
        }
    }
}
