package com.grassion.sweatiq.common

import org.json.JSONArray
import org.json.JSONObject

/** One simulated sensor reading as it travels from the Control app to the Device app. */
data class Reading(
    val seq: Int,
    val ts: Long,
    val na: Double,
    val k: Double,
    val cl: Double,
    val rate: Double,
    val skinT: Double,
    val ambT: Double,
    val scenario: String
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("seq", seq)
        o.put("ts", ts)
        o.put("na", na)
        o.put("k", k)
        o.put("cl", cl)
        o.put("rate", rate)
        o.put("skinT", skinT)
        o.put("ambT", ambT)
        o.put("scenario", scenario)
        o.put("src", "SIMULATION")
        return o.toString()
    }

    companion object {
        fun parse(s: String): Reading? {
            return try {
                val o = JSONObject(s)
                Reading(
                    o.getInt("seq"), o.getLong("ts"),
                    o.getDouble("na"), o.getDouble("k"), o.getDouble("cl"), o.getDouble("rate"),
                    o.optDouble("skinT", 32.5), o.optDouble("ambT", 28.0),
                    o.optString("scenario", "")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

class MetricStat(val avg: Double, val min: Double, val max: Double) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("avg", avg)
        o.put("min", min)
        o.put("max", max)
        return o
    }

    companion object {
        fun from(o: JSONObject) = MetricStat(o.getDouble("avg"), o.getDouble("min"), o.getDouble("max"))

        fun of(values: List<Double>): MetricStat {
            if (values.isEmpty()) return MetricStat(0.0, 0.0, 0.0)
            return MetricStat(values.average(), values.min(), values.max())
        }
    }
}

class SessionSummary(
    val id: String,
    val athleteId: String,
    val athleteName: String,
    val sport: String,
    val startTs: Long,
    val endTs: Long,
    val durationMs: Long,
    val count: Int,
    val interruptions: Int,
    val na: MetricStat,
    val k: MetricStat,
    val cl: MetricStat,
    val rate: MetricStat
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("athleteId", athleteId)
        o.put("athleteName", athleteName)
        o.put("sport", sport)
        o.put("startTs", startTs)
        o.put("endTs", endTs)
        o.put("durationMs", durationMs)
        o.put("count", count)
        o.put("interruptions", interruptions)
        o.put("na", na.toJson())
        o.put("k", k.toJson())
        o.put("cl", cl.toJson())
        o.put("rate", rate.toJson())
        return o
    }

    companion object {
        fun from(o: JSONObject) = SessionSummary(
            o.getString("id"), o.getString("athleteId"), o.getString("athleteName"),
            o.getString("sport"), o.getLong("startTs"), o.getLong("endTs"),
            o.getLong("durationMs"), o.getInt("count"), o.getInt("interruptions"),
            MetricStat.from(o.getJSONObject("na")), MetricStat.from(o.getJSONObject("k")),
            MetricStat.from(o.getJSONObject("cl")), MetricStat.from(o.getJSONObject("rate"))
        )

        fun build(
            id: String, athleteId: String, athleteName: String, sport: String,
            startTs: Long, endTs: Long, durationMs: Long, interruptions: Int,
            list: List<Reading>
        ) = SessionSummary(
            id, athleteId, athleteName, sport, startTs, endTs, durationMs, list.size, interruptions,
            MetricStat.of(list.map { it.na }), MetricStat.of(list.map { it.k }),
            MetricStat.of(list.map { it.cl }), MetricStat.of(list.map { it.rate })
        )

        fun listToJson(l: List<SessionSummary>): String {
            val a = JSONArray()
            for (s in l) a.put(s.toJson())
            return a.toString()
        }

        fun listFromJson(s: String?): MutableList<SessionSummary> {
            val out = ArrayList<SessionSummary>()
            if (s.isNullOrEmpty()) return out
            try {
                val a = JSONArray(s)
                for (i in 0 until a.length()) out.add(from(a.getJSONObject(i)))
            } catch (e: Exception) {
            }
            return out
        }
    }
}
