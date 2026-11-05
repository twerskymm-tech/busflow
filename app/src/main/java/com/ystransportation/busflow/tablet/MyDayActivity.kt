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
        /** Extra trip (split bus, charter…): navigable when it has stops */
        val tripId: String? = j.optString("extra_trip_ref").takeIf { it.isNotEmpty() && it != "null" }
        val navigable: Boolean get() = routeId != null || tripId != null
        val busId: String? = j.optString("bus_id").takeIf { it.isNotEmpty() && it != "null" }
        /** How far it got today (from BusFlow, any tablet) */
        val finished: Boolean = j.optJSONObject("progress")?.optBoolean("finished") ?: false
        val stopsDone: Int = j.optJSONObject("progress")?.optInt("done") ?: 0
        val paused: Boolean = j.optJSONObject("progress")?.optBoolean("paused") ?: false
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
    /** Bus questions already answered on this sign-in: "date|shiftStart|bus" */
    private val asked = HashSet<String>()

    private fun isDone(r: RunInfo) = r.finished || Prefs.doneRuns(this).contains("$date|${r.id}")
    private fun clockedIn(s: Shift): Boolean { val p = s.punch; return (p != null && p.isNull("clock_out_time")) || Prefs.localPunch(this) == s.start }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs.session(this) == null) { toSignIn(); return }
        b = ActivityMydayBinding.inflate(layoutInflater)
        setContentView(b.root)
        adapter = TwoLineAdapter(this)
        b.runs.adapter = adapter
        b.hello.text = "Hi, ${Prefs.driverName(this).substringBefore(" ")}"
        b.signOut.setOnClickListener { confirmSignOut() }
        b.updateApp.setOnClickListener { Updater.run(this, lifecycleScope) }
        b.swipe.onSwiped = { onSwipe() }
        b.runs.setOnItemClickListener { _, _, pos, _ -> current?.let { s -> s.runs.getOrNull(pos)?.let { onRunTap(s, it) } } }
    }

    override fun onResume() {
        super.onResume()
        Owner.keepLocked(this)
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
                    Owner.applyUnlock(this@MyDayActivity, j)
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
        val next = s.runs.firstOrNull { it.navigable && !isDone(it) }
        b.shiftTitle.text = "${s.label.ifEmpty { "Today" }} · ${s.runs.size} route${if (s.runs.size == 1) "" else "s"}".uppercase()
        adapter.rows = s.runs.map { r ->
            val isDone = isDone(r)
            Row(
                line1 = r.code,
                line2 = listOfNotNull(
                    fmtTime(r.j.optString("leave_yard_time")).ifEmpty { null }?.let { "Leave yard $it" },
                    fmtTime(r.j.optString("start_time")).ifEmpty { null }?.let { "first stop $it" },
                    r.bus?.let { "Bus $it" },
                    r.j.optString("closed_note").takeIf { it.isNotEmpty() && it != "null" },
                    if (isDone && r.navigable) "tap to open again" else if (!isDone && (r.paused || r.stopsDone > 0)) "${r.stopsDone} stops done — tap to continue" else null,
                ).joinToString(" · "),
                tag = when { isDone -> "Done"; r.paused -> "Paused"; r.stopsDone > 0 -> "Started"; r == next -> "Next"; r.routeId == null && r.tripId == null -> "Extra trip"; r.routeId == null -> "Trip"; else -> null },
                tagColor = if (isDone) Color.parseColor("#64748B") else Color.parseColor("#047857"),
                tagBg = if (isDone) Color.parseColor("#F1F5F9") else Color.parseColor("#D1FAE5"),
                dim = isDone,
            )
        }
        // On a different bus (or no bus) than this tablet's: ask if he was told to take this one
        if (!cached) askBus(s)
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
                b.action.text = if (next.paused || next.stopsDone > 0) "Continue route ${next.code}" else "Start route ${next.code}"
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
                val first = s.runs.firstOrNull { it.navigable && !isDone(it) }
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
            if (after && r.navigable && !isDone(r)) rest.put(JSONObject().put("run_id", r.id).put("route_id", r.routeId ?: "").put("trip_id", r.tripId ?: "").put("code", r.code))
        }
        startActivity(
            Intent(this, NavActivity::class.java)
                .putExtra("route_id", run.routeId)
                .putExtra("trip_id", run.tripId)
                .putExtra("run_id", run.id)
                .putExtra("date", date)
                .putExtra("chain", rest.toString())
                .putExtra("stops_by_route", (j.optJSONObject("stops_by_route") ?: JSONObject()).toString()),
        )
    }

    /** "Were you told to take this bus?" for his routes still to drive on another bus (or none yet). */
    private fun askBus(s: Shift) {
        val bus = tabletBus ?: return
        val left = s.runs.filter { !isDone(it) && it.bus != bus }
        if (left.isEmpty()) return
        val key = "$date|${s.start}|$bus"
        if (!asked.add(key)) {
            // Said No: keep reminding him on the screen
            val other = left.mapNotNull { it.bus }.distinct()
            if (other.isNotEmpty()) { b.warning.text = "You are on bus ${other.joinToString(", ")} today — this tablet is in bus $bus."; b.warning.visibility = View.VISIBLE }
            return
        }
        val others = left.mapNotNull { it.bus }.distinct()
        val names = left.joinToString(", ") { it.code }
        val msg = if (others.isEmpty()) "No bus is set yet for $names.\n\nAre you taking bus $bus?"
            else "Today you are on bus ${others.joinToString(", ")} for $names.\n\nWere you told to take bus $bus instead?"
        AlertDialog.Builder(this)
            .setTitle("This is bus $bus")
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton("Yes, I'm taking bus $bus") { _, _ -> takeBus(left) }
            .setNegativeButton("No") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle(if (others.isEmpty()) "Call the office" else "Go to bus ${others.joinToString(", ")}")
                    .setMessage(if (others.isEmpty()) "Ask the office which bus to take." else "Sign out here and sign in on the tablet of bus ${others.joinToString(", ")}.")
                    .setPositiveButton("Sign out") { _, _ -> lifecycleScope.launch { signOut() } }
                    .setNegativeButton("Stay", null)
                    .show()
                render(false)
            }
            .show()
    }

    private fun takeBus(runs: List<RunInfo>) {
        busy = true
        b.status.text = "Changing to bus $tabletBus…"
        lifecycleScope.launch {
            try {
                val ids = JSONArray().apply { runs.forEach { put(it.id) } }
                Api.call(this@MyDayActivity, JSONObject().put("action", "take_bus").put("run_ids", ids))
                toast("Done — your routes are on bus $tabletBus now. The office was told.")
            } catch (e: ApiError) {
                toast(e.message ?: "Could not change the bus")
            } finally {
                busy = false
                load()
            }
        }
    }

    /** Tap a route: start it, continue it, or open a finished one again (it continues where it stopped). */
    private fun onRunTap(s: Shift, r: RunInfo) {
        if (!r.navigable) { toast("This trip has no stops to navigate. Check with the office."); return }
        if (!clockedIn(s)) { toast("Swipe to clock in first."); return }
        val done = isDone(r)
        AlertDialog.Builder(this)
            .setTitle(if (done) "Open ${r.code} again?" else "Start ${r.code}?")
            .setMessage(when {
                done -> "It opens where you left off. Stops already done stay done."
                r.stopsDone > 0 -> "${r.stopsDone} stops are already done. It continues from the next stop."
                else -> null
            })
            .setPositiveButton(if (done) "Open" else "Start") { _, _ ->
                Prefs.unmarkRunDone(this, date, r.id)
                startRoute(s, r)
            }
            .setNegativeButton("Cancel", null)
            .show()
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
