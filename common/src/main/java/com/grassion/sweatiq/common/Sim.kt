package com.grassion.sweatiq.common

import java.util.Random

enum class Scenario(
    val label: String,
    val desc: String,
    val na: Double,
    val k: Double,
    val cl: Double,
    val rate: Double,
    val dNa: Double,
    val dRate: Double
) {
    REST("Resting", "Low sweat rate", 38.0, 4.4, 29.0, 0.15, 0.0, 0.0),
    WARM_UP("Warm Up", "Gradual increase", 42.0, 4.8, 32.0, 0.45, 0.12, 0.012),
    NORMAL("Normal Training", "Moderate-intensity session", 46.0, 5.1, 34.5, 0.80, 0.0, 0.0),
    HIGH("High Intensity Run", "High sweat rate, rising electrolytes", 53.0, 5.9, 39.0, 1.15, 0.0, 0.0),
    HEAT("Heat & Dehydration", "Electrolyte concentration climbs", 50.0, 5.6, 38.0, 1.00, 0.25, 0.01),
    RECOVERY("Hydration Recovery", "Sodium falls after rehydration", 48.0, 5.0, 35.0, 0.60, -0.25, -0.01),
    INTERVAL("Interval Training", "Alternating high and low effort", 46.0, 5.1, 34.5, 0.80, 0.0, 0.0),
    COOL("Cool Down", "Gradual decrease", 44.0, 4.8, 32.0, 0.40, -0.12, -0.01)
}

/** Generates plausible, smoothly changing demo values. These are NOT measurements. */
class SimEngine {
    var scenario: Scenario = Scenario.NORMAL
        private set
    var variation = true
    var na = 45.0
    var k = 5.0
    var cl = 35.0
    var rate = 0.75

    private var step = 0
    private var offNa = 0.0
    private var offRate = 0.0
    private val rnd = Random()

    fun setScenario(s: Scenario) {
        scenario = s
        step = 0
        offNa = 0.0
        offRate = 0.0
    }

    fun setCurrent(nNa: Double, nK: Double, nCl: Double, nRate: Double) {
        na = nNa
        k = nK
        cl = nCl
        rate = nRate
    }

    private fun noise(amp: Double): Double = if (variation) (rnd.nextDouble() - 0.5) * 2.0 * amp else 0.0

    /** Advance one step and return the new values. */
    fun next(): Reading {
        step++
        var tNa = scenario.na
        var tK = scenario.k
        var tCl = scenario.cl
        var tRate = scenario.rate
        if (scenario == Scenario.INTERVAL) {
            val hi = (step / 8) % 2 == 1
            tNa = if (hi) 53.0 else 42.0
            tK = if (hi) 5.9 else 4.7
            tCl = if (hi) 39.0 else 31.5
            tRate = if (hi) 1.15 else 0.55
        }
        offNa = (offNa + scenario.dNa).coerceIn(-15.0, 20.0)
        offRate = (offRate + scenario.dRate).coerceIn(-0.6, 0.8)
        tNa += offNa
        tK += offNa * 0.04
        tCl += offNa * 0.45
        tRate += offRate
        val a = 0.25
        na = (na + (tNa - na) * a + noise(0.45)).coerceIn(20.0, 90.0)
        k = (k + (tK - k) * a + noise(0.05)).coerceIn(2.0, 9.0)
        cl = (cl + (tCl - cl) * a + noise(0.4)).coerceIn(15.0, 70.0)
        rate = (rate + (tRate - rate) * a + noise(0.02)).coerceIn(0.1, 2.5)
        return Reading(
            0, System.currentTimeMillis(),
            Math.round(na * 10) / 10.0, Math.round(k * 100) / 100.0,
            Math.round(cl * 10) / 10.0, Math.round(rate * 100) / 100.0,
            32.5 + noise(0.3), 28.0 + noise(0.2), scenario.label
        )
    }
}
