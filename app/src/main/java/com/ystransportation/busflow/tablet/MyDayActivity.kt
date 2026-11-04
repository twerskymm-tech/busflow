package com.ystransportation.busflow.tablet

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ystransportation.busflow.tablet.databinding.ActivityMydayBinding
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * The signed-in driver's day on the bus tablet:
 * swipe to clock in → his first route opens by itself → after each route the next one →
 * back here to swipe out → signed out for the next driver.
 */
class MyDayActivity : AppCompatActivity() {
    private class RunInfo(val j: JSONObject) {
        val id: String = j.optString("id")
        val routeId: String? = j.optString("route_id").takeIf { it.isNotEmpty() && it != "null" }
        val code: String = j.optString("route_code").ifEmpty { j.optString("route_label") }.ifEmpty { "Extra trip" }
        val bus: String? = j.optString("bus_fleet_number").takeIf { it.isNotEmpty() && it != "null" }
    }

    private class Shift(val j: JSONObject) {
        val label: String = j.optString("label")
        val start: String = j.optString("start_datetime")
        val endMs: Long = parseMs(j.optString("end_datetime"))
        val openMs: Long = j.optJSONObject("clock_in_window")?.optLong("openMs") ?: 0L
        val punch: JSONObject? = j.optJSONObject("punch")
        val runs: List<RunInfo> = j.optJSONArray("runs").let { a -> if (a == null) emptyList() else (0 until a.length()).map { RunInfo(a.getJSONObject(it)) } }
    }

    private lateinit var b: ActivityMydayBinding
    private lateinit var adapter: TwoLineAdapter
    private var day: JSONObject? = null
    private var date = ""
    private var tabletBus: String? = null
    private var current: Shift? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs.session(this) == null) { toSignIn(); return }
        b = ActivityMydayBinding.inflate(layoutInflater)
        setContentView(b.root)
        adapter = TwoLineAdapter(this)
        b.runs.adapter = adapter
        b.hello.text = "Hi, ${Prefs.driverName(this).substringBefore(" ")}"
        b.signOut.setOnClickListener { confirmSignOut() }
        b.swipe.onSwiped = { onSwipe() }
    }

    override fun onResume() {
        super.onResume()
        if (::b.isInitialized) load()
    }

    override fun onPause() {
        super.onPause()
        stopPing()
    }

    // ── Between routes (clocked in, not driving a route on the tablet): the office still sees the bus live ──
    private var pingJob: kotlinx.coroutines.Job? = null
    private var pingListener: android.location.LocationListener? = null
    private var pingLoc: android.location.Location? = null
    private var posId: String? = null

    @android.annotation.SuppressLint("MissingPermission")
    private fun startPing() {
        if (pingJob != null) return
        val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        val l = object : android.location.LocationListener {
            override fun onLocationChanged(location: android.location.Location) { pingLoc = location }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        try { lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 2000L, 0f, l, mainLooper) } catch (e: Exception) { }
        pingListener = l
        pingJob = lifecycleScope.launch {
            var lastSent = 0L
            while (true) {
                kotlinx.coroutines.delay(5000)
                val loc = pingLoc ?: continue
                if (loc.time == lastSent) continue
                try {
                    val j = Api.call(this@MyDayActivity, JSONObject().put("action", "pos").put("lat", loc.latitude).put("lng", loc.longitude)
                        .put("at", loc.time).put("heading", if (loc.hasBearing()) loc.bearing.toDouble() else JSONObject.NULL)
                        .put("speed_mph", if (loc.hasSpeed()) loc.speed * 2.237 else JSONObject.NULL)
                        .put("state", "between_routes").put("pos_id", posId ?: JSONObject.NULL))
                    posId = j.optString("pos_id").ifEmpty { posId }
                    lastSent = loc.time
                } catch (e: ApiError) { /* try again in 5 s */ }
            }
        }
    }

    private fun stopPing() {
        pingJob?.cancel(); pingJob = null
        pingListener?.let { try { (getSystemService(LOCATION_SERVICE) as android.location.LocationManager).removeUpdates(it) } catch (e: Exception) { } }
        pingListener = null
    }

    private fun load() {
        b.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                Api.flushQueue(this@MyDayActivity)
                Api.flushTracks(this@MyDayActivity)
                val (j, cached) = Api.myDay(this@MyDayActivity)
                day = j
                date = j.optString("date")
                tabletBus = j.optJSONObject("bus")?.optString("fleet_number")?.takeIf { it.isNotEmpty() }
                render(cached)
            } catch (e: ApiError) {
                if (e.signedOut || e.unpaired) { Prefs.signOut(this@MyDayActivity); toSignIn(); return@launch }
                b.status.text = e.message
                b.swipe.visibility = View.GONE
            } finally {
                b.progress.visibility = View.GONE
            }
        }
    }

    private fun render(cached: Boolean) {
        val j = day ?: return
        val shifts = j.optJSONArray("shifts").let { a -> if (a == null) emptyList() else (0 until a.length()).map { Shift(a.getJSONObject(it)) } }
        b.sub.text = listOfNotNull(tabletBus?.let { "Bus $it" }, if (cached) "no signal" else null).joinToString(" · ")
        val now = System.currentTimeMillis()
        val local = Prefs.localPunch(this)
        // The shift he is on now: clocked in and not out yet, or not finished yet
        current = shifts.firstOrNull { s ->
            val p = s.punch
            (p != null && p.isNull("clock_out_time")) || local == s.start || (p == null && s.endMs + 60 * 60000 > now)
        }
        val s = current
        b.warning.visibility = View.GONE
        b.action.visibility = View.GONE
        if (shifts.isEmpty() || s == null) {
            b.shiftTitle.text = ""
            adapter.rows = emptyList()
            b.swipe.visibility = View.GONE
            b.status.text = if (shifts.isEmpty()) "No route for you today.\nCall the office if that's wrong." else "You're done for today. 👍"
            val phone = cachedOfficePhone()
            if (phone.isNotEmpty()) {
                b.action.text = "Call the office"
                b.action.visibility = View.VISIBLE
                b.action.setOnClickListener { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))) }
            }
            return
        }
        val done = Prefs.doneRuns(this)
        val next = s.runs.firstOrNull { it.routeId != null && !done.contains("$date|${it.id}") }
        b.shiftTitle.text = "${s.label.ifEmpty { "Today" }} · ${s.runs.size} route${if (s.runs.size == 1) "" else "s"}".uppercase()
        adapter.rows = s.runs.map { r ->
            val isDone = done.contains("$date|${r.id}")
            Row(
                line1 = r.code,
                line2 = listOfNotNull(
                    fmtTime(r.j.optString("leave_yard_time")).ifEmpty { null }?.let { "Leave yard $it" },
                    fmtTime(r.j.optString("start_time")).ifEmpty { null }?.let { "first stop $it" },
                    r.bus?.let { "Bus $it" },
                    r.j.optString("closed_note").takeIf { it.isNotEmpty() && it != "null" },
                ).joinToString(" · "),
                tag = when { isDone -> "Done"; r == next -> "Next"; r.routeId == null -> "Extra trip"; else -> null },
                tagColor = if (isDone) Color.parseColor("#64748B") else Color.parseColor("#047857"),
                tagBg = if (isDone) Color.parseColor("#F1F5F9") else Color.parseColor("#D1FAE5"),
                dim = isDone,
            )
        }
        // On a different bus than his today
        val otherBus = s.runs.mapNotNull { it.bus }.firstOrNull { tabletBus != null && it != tabletBus }
        if (otherBus != null) {
            b.warning.text = "Today you are on bus $otherBus — this tablet is in bus $tabletBus. The office will be told when you clock in."
            b.warning.visibility = View.VISIBLE
        }
        val p = s.punch
        val clockedIn = (p != null && p.isNull("clock_out_time")) || local == s.start
        b.swipe.visibility = View.VISIBLE
        b.swipe.isEnabled = !busy
        if (!clockedIn) {
            b.status.text = if (s.openMs > now) "Clock-in opens at ${fmtMs(s.openMs)}" else "Ready to go"
            b.swipe.setText("Swipe to clock in  »")
            b.swipe.setColor(ContextCompat.getColor(this, R.color.brand))
            b.swipe.reverse = false
        } else {
            startPing()
            b.status.text = if (p != null) "Clocked in at ${fmtMs(parseMs(p.optString("clock_in_time")))}" else "Clocked in (will send when there's signal)"
            b.swipe.setText("«  Swipe to clock out")
            b.swipe.setColor(Color.parseColor("#B91C1C"))
            b.swipe.reverse = true
            if (next != null) {
                b.action.text = "Start route ${next.code}"
                b.action.visibility = View.VISIBLE
                b.action.setOnClickListener { startRoute(s, next) }
            }
        }
    }

    private fun onSwipe() {
        val s = current ?: return
        if (busy) return
        val p = s.punch
        val clockedIn = (p != null && p.isNull("clock_out_time")) || Prefs.localPunch(this) == s.start
        if (!clockedIn) clockIn(s) else askFuelThenClockOut(s)
    }

    private fun clockIn(s: Shift) {
        busy = true
        b.status.text = "Clocking in…"
        lifecycleScope.launch {
            try {
                val loc = Where.now(this@MyDayActivity)
                if (loc == null) { toast("Can't find your location yet. Wait a moment and swipe again."); return@launch }
                val runIds = JSONArray().apply { s.runs.forEach { put(it.id) } }
                val body = JSONObject().put("kind", "in").put("run_ids", runIds)
                    .put("lat", loc.latitude).put("lng", loc.longitude).put("accuracy", loc.accuracy.toDouble())
                s.runs.mapNotNull { it.bus }.firstOrNull()?.let { body.put("assigned_bus", it) }
                try {
                    Api.punch(this@MyDayActivity, JSONObject(body.toString()))
                } catch (e: ApiError) {
                    if (!e.offline) { toast(e.message ?: "Could not clock in"); return@launch }
                    // No signal: saved with the real time and place, sent later
                    queue(body.put("offline_at", Instant.now().toString()))
                    Prefs.setLocalPunch(this@MyDayActivity, s.start)
                    toast("No signal — clock-in saved, it will be sent later")
                }
                val first = s.runs.firstOrNull { it.routeId != null && !Prefs.doneRuns(this@MyDayActivity).contains("$date|${it.id}") }
                if (first != null) startRoute(s, first) else load()
            } finally {
                busy = false
            }
        }
    }

    private fun askFuelThenClockOut(s: Shift) {
        val levels = arrayOf("Full", "3/4", "1/2", "1/4", "Empty")
        AlertDialog.Builder(this)
            .setTitle("How much fuel is in the bus?")
            .setItems(levels) { _, i -> clockOut(s, levels[i]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clockOut(s: Shift, fuel: String) {
        busy = true
        b.status.text = "Clocking out…"
        lifecycleScope.launch {
            try {
                val loc = Where.now(this@MyDayActivity)
                if (loc == null) { toast("Can't find your location yet. Wait a moment and swipe again."); return@launch }
                val body = JSONObject().put("kind", "out").put("punch_id", s.punch?.optString("id") ?: "")
                    .put("fuel_level", fuel).put("lat", loc.latitude).put("lng", loc.longitude).put("accuracy", loc.accuracy.toDouble())
                try {
                    if (body.optString("punch_id").isEmpty()) throw ApiError("waiting for clock-in", offline = true)
                    Api.punch(this@MyDayActivity, JSONObject(body.toString()))
                } catch (e: ApiError) {
                    if (!e.offline) { toast(e.message ?: "Could not clock out"); return@launch }
                    queue(body.put("offline_at", Instant.now().toString()))
                    toast("No signal — clock-out saved, it will be sent later")
                }
                toast("Clocked out. Have a good day!")
                signOut()
            } finally {
                busy = false
            }
        }
    }

    private fun startRoute(s: Shift, run: RunInfo) {
        val j = day ?: return
        val rest = JSONArray()
        var after = false
        for (r in s.runs) {
            if (r == run) { after = true; continue }
            if (after && r.routeId != null) rest.put(JSONObject().put("run_id", r.id).put("route_id", r.routeId).put("code", r.code))
        }
        startActivity(
            Intent(this, NavActivity::class.java)
                .putExtra("route_id", run.routeId)
                .putExtra("run_id", run.id)
                .putExtra("date", date)
                .putExtra("chain", rest.toString())
                .putExtra("stops_by_route", (j.optJSONObject("stops_by_route") ?: JSONObject()).toString()),
        )
    }

    private fun queue(item: JSONObject) {
        val q = Prefs.queue(this)
        q.put(item)
        Prefs.setQueue(this, q)
    }

    private fun confirmSignOut() {
        val s = current
        val p = s?.punch
        val clockedIn = s != null && ((p != null && p.isNull("clock_out_time")) || Prefs.localPunch(this) == s.start)
        AlertDialog.Builder(this)
            .setTitle("Sign out?")
            .setMessage(if (clockedIn) "You are still clocked in. Sign out anyway? (You stay on the clock.)" else "The next driver can sign in after you.")
            .setPositiveButton("Sign out") { _, _ -> lifecycleScope.launch { signOut() } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private suspend fun signOut() {
        stopPing()
        Api.logout(this)
        Prefs.signOut(this)
        toSignIn()
    }

    private fun toSignIn() {
        startActivity(Intent(this, SignInActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun cachedOfficePhone(): String = try {
        JSONObject(java.io.File(filesDir, "home.json").readText()).optString("office_phone")
    } catch (e: Exception) { "" }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    companion object {
        fun parseMs(iso: String?): Long = try { if (iso.isNullOrEmpty() || iso == "null") 0L else Instant.parse(iso).toEpochMilli() } catch (e: Exception) {
            try { ZonedDateTime.parse(iso).toInstant().toEpochMilli() } catch (e2: Exception) { 0L }
        }
        fun fmtMs(ms: Long): String = if (ms <= 0) "" else DateTimeFormatter.ofPattern("h:mm a").format(Instant.ofEpochMilli(ms).atZone(NY))
    }
}
