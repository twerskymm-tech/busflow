package com.ystransportation.busflow.tablet

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

val DAYS = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
val DAY_SHORT = mapOf("Sunday" to "Sun", "Monday" to "Mon", "Tuesday" to "Tue", "Wednesday" to "Wed", "Thursday" to "Thu", "Friday" to "Fri", "Saturday" to "Sat")
val NY: ZoneId = ZoneId.of("America/New_York")

fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }

data class RouteItem(
    val id: String, val code: String, val label: String, val direction: String, val school: String,
    val days: List<String>, val runsToday: Boolean, val approvedDays: List<String>,
) {
    companion object {
        fun from(j: JSONObject) = RouteItem(
            j.optString("id"), j.optString("code"), j.optString("label"), j.optString("direction"), j.optString("school"),
            j.optJSONArray("days").strings(), j.optBoolean("runs_today"), j.optJSONArray("approved_days").strings(),
        )
    }
}

data class Stop(
    val id: String, val number: Int, val name: String, val type: String, val time: String?,
    val lat: Double, val lng: Double, val curbLat: Double?, val curbLng: Double?,
) {
    /** Short name for the screen and voice: the part before " / ", without "(W)" style notes. */
    val shortName: String get() = name.substringBefore(" / ").replace(Regex("\\s*\\([^)]*\\)"), "").trim().ifEmpty { name }

    companion object {
        fun from(j: JSONObject) = Stop(
            j.optString("id"), j.optInt("number"), j.optString("name"), j.optString("type", "Pickup"),
            j.optString("time").takeIf { it.isNotEmpty() && it != "null" },
            j.optDouble("lat"), j.optDouble("lng"),
            if (j.isNull("curb_lat")) null else j.optDouble("curb_lat"),
            if (j.isNull("curb_lng")) null else j.optDouble("curb_lng"),
        )
    }
}

data class Plan(
    val json: JSONObject,
    val routeId: String, val code: String, val direction: String, val date: String, val day: String,
    val pathState: String, val polyline: String?, val pathIncludesYard: Boolean,
    val stops: List<Stop>, val yardLat: Double?, val yardLng: Double?, val missingLocations: Int,
) {
    val hasPath get() = pathState == "approved" && !polyline.isNullOrEmpty()

    companion object {
        fun from(j: JSONObject): Plan {
            val r = j.optJSONObject("route") ?: JSONObject()
            val path = j.optJSONObject("path")
            val yard = j.optJSONObject("yard")
            val stops = j.optJSONArray("stops")
            return Plan(
                j, r.optString("id"), r.optString("code"), r.optString("direction"), j.optString("date"), j.optString("day"),
                j.optString("path_state", "none"), path?.optString("polyline"), path?.optBoolean("includes_yard", true) ?: false,
                if (stops == null) emptyList() else (0 until stops.length()).map { Stop.from(stops.getJSONObject(it)) },
                yard?.optDouble("lat"), yard?.optDouble("lng"), j.optInt("stops_missing_location", 0),
            )
        }
    }
}

/** "08:04" → "8:04 AM" */
fun fmtTime(t: String?): String {
    val m = Regex("^(\\d{1,2}):(\\d{2})").find(t ?: "") ?: return ""
    val h = m.groupValues[1].toInt()
    return "${(h + 11) % 12 + 1}:${m.groupValues[2]} ${if (h >= 12) "PM" else "AM"}"
}

fun todayNY(): LocalDate = LocalDate.now(NY)

/** The next date (today included) that falls on this day name. */
fun nextDateFor(dayName: String): LocalDate {
    val target = DayOfWeek.valueOf(dayName.uppercase())
    var d = todayNY()
    while (d.dayOfWeek != target) d = d.plusDays(1)
    return d
}

fun dayNameOf(d: LocalDate): String = DAYS[d.dayOfWeek.value % 7]

fun fmtMiles(meters: Double): String = if (meters < 160) "${(meters * 3.28084).toInt()} ft" else String.format("%.1f mi", meters / 1609.34)
