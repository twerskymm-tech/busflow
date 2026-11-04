package com.ystransportation.busflow.tablet

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Talks to the BusFlow backend function `tabletApi`. */
class ApiError(
    message: String,
    val unpaired: Boolean = false,
    val signedOut: Boolean = false,
    /** No connection at all (as opposed to BusFlow saying no). */
    val offline: Boolean = false,
) : Exception(message)

object Prefs {
    private const val FILE = "busflow"
    const val DEFAULT_URL = "https://bus-flow-fleet.base44.app/functions/tabletApi"

    private fun p(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun key(ctx: Context): String? = p(ctx).getString("key", null)
    fun tabletName(ctx: Context): String = p(ctx).getString("tablet_name", "") ?: ""
    fun baseUrl(ctx: Context): String = p(ctx).getString("base_url", null) ?: DEFAULT_URL
    fun setPaired(ctx: Context, key: String, name: String) {
        p(ctx).edit().putString("key", key).putString("tablet_name", name).apply()
    }
    fun setBaseUrl(ctx: Context, url: String?) {
        p(ctx).edit().apply { if (url.isNullOrBlank()) remove("base_url") else putString("base_url", url.trim()) }.apply()
    }
    fun unpair(ctx: Context) {
        p(ctx).edit().remove("key").remove("tablet_name").apply()
        signOut(ctx)
    }
    fun muted(ctx: Context): Boolean = p(ctx).getBoolean("muted", false)
    fun setMuted(ctx: Context, v: Boolean) { p(ctx).edit().putBoolean("muted", v).apply() }

    // The driver signed in on this tablet right now
    fun session(ctx: Context): String? = p(ctx).getString("session", null)
    fun driverName(ctx: Context): String = p(ctx).getString("driver_name", "") ?: ""
    fun driverId(ctx: Context): String = p(ctx).getString("driver_id", "") ?: ""
    fun setSession(ctx: Context, session: String, driverId: String, name: String) {
        p(ctx).edit().putString("session", session).putString("driver_id", driverId).putString("driver_name", name).apply()
    }
    fun signOut(ctx: Context) {
        p(ctx).edit().remove("session").remove("driver_id").remove("driver_name").remove("done_runs").remove("local_punch").apply()
    }

    /** Runs this driver finished on this tablet today ("date|runId"). */
    fun doneRuns(ctx: Context): Set<String> = p(ctx).getStringSet("done_runs", emptySet()) ?: emptySet()
    fun markRunDone(ctx: Context, date: String, runId: String) {
        p(ctx).edit().putStringSet("done_runs", doneRuns(ctx) + "$date|$runId").apply()
    }

    /** A clock-in made with no signal, not yet confirmed by BusFlow: "shiftStartIso" of that shift. */
    fun localPunch(ctx: Context): String? = p(ctx).getString("local_punch", null)
    fun setLocalPunch(ctx: Context, shiftStart: String?) {
        p(ctx).edit().apply { if (shiftStart == null) remove("local_punch") else putString("local_punch", shiftStart) }.apply()
    }

    /** Clock in/out waiting for a signal, oldest first. */
    fun queue(ctx: Context): JSONArray = try { JSONArray(p(ctx).getString("punch_queue", "[]")) } catch (e: Exception) { JSONArray() }
    fun setQueue(ctx: Context, q: JSONArray) { p(ctx).edit().putString("punch_queue", q.toString()).apply() }
}

object Api {
    /** POST a JSON body; returns the JSON answer or throws [ApiError]. */
    suspend fun call(ctx: Context, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        body.put("app_version", BuildConfig.VERSION_NAME)
        val conn = (URL(Prefs.baseUrl(ctx)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            Prefs.key(ctx)?.let { setRequestProperty("X-Tablet-Key", it) }
            Prefs.session(ctx)?.let { setRequestProperty("X-Driver-Session", it) }
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val json = try { JSONObject(text) } catch (e: Exception) { JSONObject().put("error", "Server error ($code)") }
            if (code !in 200..299) {
                throw ApiError(json.optString("error", "Server error ($code)"), json.optBoolean("unpaired", false), json.optBoolean("signed_out", false))
            }
            json
        } catch (e: ApiError) {
            throw e
        } catch (e: Exception) {
            throw ApiError("No connection to BusFlow. Check the internet and try again.", offline = true)
        } finally {
            conn.disconnect()
        }
    }

    suspend fun pair(ctx: Context, code: String): JSONObject =
        call(ctx, JSONObject().put("action", "pair").put("code", code).put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}"))

    /** Route list; falls back to the last copy saved on the tablet when there is no signal. */
    suspend fun routes(ctx: Context): Pair<JSONObject, Boolean> = cached(ctx, "routes.json") {
        call(ctx, JSONObject().put("action", "routes"))
    }

    suspend fun route(ctx: Context, routeId: String, date: String): Pair<JSONObject, Boolean> = cached(ctx, "route_${routeId}_$date.json") {
        call(ctx, JSONObject().put("action", "route").put("route_id", routeId).put("date", date))
    }

    suspend fun home(ctx: Context): Pair<JSONObject, Boolean> = cached(ctx, "home.json") {
        call(ctx, JSONObject().put("action", "home"))
    }

    suspend fun login(ctx: Context, driverId: String, pin: String): JSONObject =
        call(ctx, JSONObject().put("action", "login").put("driver_id", driverId).put("pin", pin))

    suspend fun logout(ctx: Context) {
        try { call(ctx, JSONObject().put("action", "logout")) } catch (e: ApiError) { /* signed out on the tablet anyway */ }
    }

    suspend fun myDay(ctx: Context): Pair<JSONObject, Boolean> = cached(ctx, "myday_${Prefs.driverId(ctx)}.json") {
        call(ctx, JSONObject().put("action", "myday"))
    }

    suspend fun punch(ctx: Context, body: JSONObject): JSONObject = call(ctx, body.put("action", "punch"))

    /**
     * Sends clock in/outs made with no signal, in order, with the time they were really made.
     * A clock-out waiting for its clock-in gets the punch id once the clock-in goes through.
     */
    suspend fun flushQueue(ctx: Context): Boolean {
        var q = Prefs.queue(ctx)
        var lastPunchId: String? = null
        while (q.length() > 0) {
            val item = q.getJSONObject(0)
            if (item.optString("kind") == "out" && item.optString("punch_id").isEmpty()) {
                if (lastPunchId == null) { drop(ctx); q = Prefs.queue(ctx); continue }
                item.put("punch_id", lastPunchId)
            }
            try {
                val res = punch(ctx, JSONObject(item.toString()))
                if (item.optString("kind") == "in") lastPunchId = res.optString("punch_id").ifEmpty { null }
            } catch (e: ApiError) {
                if (e.offline) return false
                // BusFlow said no (e.g. already clocked out): drop it so the queue doesn't get stuck
            }
            drop(ctx)
            q = Prefs.queue(ctx)
        }
        Prefs.setLocalPunch(ctx, null)
        return true
    }

    private fun drop(ctx: Context) {
        val q = Prefs.queue(ctx)
        val rest = JSONArray()
        for (i in 1 until q.length()) rest.put(q.get(i))
        Prefs.setQueue(ctx, rest)
    }

    /** Returns (answer, fromCache). */
    private suspend fun cached(ctx: Context, name: String, fetch: suspend () -> JSONObject): Pair<JSONObject, Boolean> {
        val file = File(ctx.filesDir, name)
        return try {
            val j = fetch()
            withContext(Dispatchers.IO) { file.writeText(j.toString()) }
            j to false
        } catch (e: ApiError) {
            if (!e.offline || !file.exists()) throw e
            val j = withContext(Dispatchers.IO) { JSONObject(file.readText()) }
            j to true
        }
    }
}
