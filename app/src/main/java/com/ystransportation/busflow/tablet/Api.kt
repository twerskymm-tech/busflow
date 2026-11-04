package com.ystransportation.busflow.tablet

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Talks to the BusFlow backend function `tabletApi`. */
class ApiError(message: String, val unpaired: Boolean = false) : Exception(message)

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
    }
    fun muted(ctx: Context): Boolean = p(ctx).getBoolean("muted", false)
    fun setMuted(ctx: Context, v: Boolean) { p(ctx).edit().putBoolean("muted", v).apply() }
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
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val json = try { JSONObject(text) } catch (e: Exception) { JSONObject().put("error", "Server error ($code)") }
            if (code !in 200..299) throw ApiError(json.optString("error", "Server error ($code)"), json.optBoolean("unpaired", false))
            json
        } catch (e: ApiError) {
            throw e
        } catch (e: Exception) {
            throw ApiError("No connection to BusFlow. Check the internet and try again.")
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

    /** Returns (answer, fromCache). */
    private suspend fun cached(ctx: Context, name: String, fetch: suspend () -> JSONObject): Pair<JSONObject, Boolean> {
        val file = File(ctx.filesDir, name)
        return try {
            val j = fetch()
            withContext(Dispatchers.IO) { file.writeText(j.toString()) }
            j to false
        } catch (e: ApiError) {
            if (e.unpaired || !file.exists()) throw e
            val j = withContext(Dispatchers.IO) { JSONObject(file.readText()) }
            j to true
        }
    }
}
