package com.ystransportation.busflow.tablet

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * Wi-Fi inside BusFlow, for a locked tablet (owner mode) where the tablet's own settings are blocked.
 * Lists nearby networks; tap one, type the password, and it connects.
 */
@Suppress("DEPRECATION")
object WifiPicker {
    fun show(activity: Activity) {
        val wm = activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wm.isWifiEnabled) runCatching { wm.isWifiEnabled = true }
        val wait = AlertDialog.Builder(activity).setTitle("Wi-Fi").setMessage("Looking for networks…").setNegativeButton("Cancel", null).show()
        var done = false
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { if (!done) { done = true; runCatching { activity.unregisterReceiver(this) }; wait.dismiss(); list(activity, wm) } }
        }
        activity.registerReceiver(rx, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        if (!runCatching { wm.startScan() }.getOrDefault(false)) { done = true; runCatching { activity.unregisterReceiver(rx) }; wait.dismiss(); list(activity, wm) }
        // Some tablets never send "scan done": show what it has after 6 s
        activity.window.decorView.postDelayed({ if (!done) { done = true; runCatching { activity.unregisterReceiver(rx) }; wait.dismiss(); list(activity, wm) } }, 6000)
    }

    private class Net(val ssid: String, val level: Int, val caps: String, val connected: Boolean) {
        val open get() = !(caps.contains("WPA") || caps.contains("WEP") || caps.contains("SAE"))
        /** 0..4 bars */
        val bars get() = when { level > -55 -> 4; level > -65 -> 3; level > -75 -> 2; level > -85 -> 1; else -> 0 }
    }

    private fun dp(a: Activity, v: Int) = (v * a.resources.displayMetrics.density).toInt()

    /** Wi-Fi wedge filled from the bottom by signal strength (like the phone's own icon). */
    private fun signalIcon(a: Activity, bars: Int): android.graphics.drawable.Drawable {
        val bg = androidx.core.content.ContextCompat.getDrawable(a, R.drawable.ic_wedge)!!.mutate().apply { setTint(androidx.core.content.ContextCompat.getColor(a, R.color.line)) }
        val fg = androidx.core.content.ContextCompat.getDrawable(a, R.drawable.ic_wedge)!!.mutate().apply { setTint(androidx.core.content.ContextCompat.getColor(a, R.color.brand)) }
        val clip = android.graphics.drawable.ClipDrawable(fg, android.view.Gravity.BOTTOM, android.graphics.drawable.ClipDrawable.VERTICAL)
        clip.level = listOf(1500, 3800, 5800, 7800, 10000)[bars.coerceIn(0, 4)]
        return android.graphics.drawable.LayerDrawable(arrayOf(bg, clip))
    }

    private fun row(a: Activity, n: Net): android.view.View {
        val ink = androidx.core.content.ContextCompat.getColor(a, R.color.ink)
        val muted = androidx.core.content.ContextCompat.getColor(a, R.color.muted)
        return android.widget.LinearLayout(a).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(a, 20), dp(a, 12), dp(a, 20), dp(a, 12))
            addView(android.widget.ImageView(a).apply { setImageDrawable(signalIcon(a, n.bars)) }, android.widget.LinearLayout.LayoutParams(dp(a, 28), dp(a, 28)))
            addView(android.widget.LinearLayout(a).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(dp(a, 16), 0, dp(a, 8), 0)
                addView(android.widget.TextView(a).apply {
                    text = n.ssid; textSize = 17f; setTextColor(ink)
                    if (n.connected) setTypeface(typeface, android.graphics.Typeface.BOLD)
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(android.widget.TextView(a).apply {
                    text = when { n.connected -> "Connected"; n.open -> "Open network"; else -> "Needs a password" }
                    textSize = 13f
                    setTextColor(if (n.connected) androidx.core.content.ContextCompat.getColor(a, R.color.ok) else muted)
                })
            }, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (!n.open) addView(android.widget.ImageView(a).apply {
                setImageResource(R.drawable.ic_lock); setColorFilter(muted)
            }, android.widget.LinearLayout.LayoutParams(dp(a, 18), dp(a, 18)))
        }
    }

    private fun list(activity: Activity, wm: WifiManager) {
        val current = runCatching { wm.connectionInfo?.ssid?.trim('"') }.getOrNull()
        val nets = runCatching { wm.scanResults }.getOrDefault(emptyList())
            .filter { it.SSID.isNotBlank() && !it.SSID.startsWith("DIRECT-") }   // printers / TVs, not internet
            .groupBy { it.SSID }.map { (_, v) -> v.maxBy { it.level } }
            .map { Net(it.SSID, it.level, it.capabilities, it.SSID == current) }
            .sortedWith(compareByDescending<Net> { it.connected }.thenByDescending { it.level })
            .take(25)
        if (nets.isEmpty()) {
            AlertDialog.Builder(activity).setTitle("Wi-Fi").setMessage("No networks found. Move closer to the Wi-Fi and try again.")
                .setPositiveButton("Try again") { _, _ -> show(activity) }.setNegativeButton("Close", null).show()
            return
        }
        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = nets.size
            override fun getItem(i: Int) = nets[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, convert: android.view.View?, parent: android.view.ViewGroup?) = row(activity, nets[i])
        }
        AlertDialog.Builder(activity).setTitle("Wi-Fi")
            .setAdapter(adapter) { _, i ->
                val n = nets[i]
                if (n.connected) return@setAdapter
                if (n.open) connect(activity, wm, n.ssid, null, n.caps) else askPassword(activity, wm, n.ssid, n.caps)
            }
            .setNeutralButton("Refresh") { _, _ -> show(activity) }
            .setNegativeButton("Close", null).show()
    }

    private fun askPassword(activity: Activity, wm: WifiManager, ssid: String, caps: String) {
        val input = EditText(activity).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD; hint = "Wi-Fi password" }
        AlertDialog.Builder(activity).setTitle(ssid).setView(input)
            .setPositiveButton("Connect") { _, _ -> connect(activity, wm, ssid, input.text.toString(), caps) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun connect(activity: Activity, wm: WifiManager, ssid: String, pass: String?, caps: String) {
        val cfg = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            when {
                pass == null -> allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                caps.contains("WEP") -> { wepKeys[0] = "\"$pass\""; wepTxKeyIndex = 0; allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE) }
                else -> preSharedKey = "\"$pass\""
            }
        }
        // Replace an older saved copy of the same network
        runCatching { wm.configuredNetworks?.filter { it.SSID == cfg.SSID }?.forEach { wm.removeNetwork(it.networkId) } }
        val id = runCatching { wm.addNetwork(cfg) }.getOrDefault(-1)
        if (id < 0) {
            Toast.makeText(activity, if (Owner.isOwner(activity)) "Couldn't add that network. Check the name and password." else "Use the phone's Wi-Fi settings.", Toast.LENGTH_LONG).show()
            return
        }
        runCatching { wm.disconnect(); wm.enableNetwork(id, true); wm.reconnect() }
        Toast.makeText(activity, "Connecting to $ssid… (wrong password = it won't connect; try again)", Toast.LENGTH_LONG).show()
    }
}
