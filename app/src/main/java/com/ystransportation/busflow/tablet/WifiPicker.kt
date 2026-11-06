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

    private fun list(activity: Activity, wm: WifiManager) {
        val nets = runCatching { wm.scanResults }.getOrDefault(emptyList())
            .filter { it.SSID.isNotBlank() }
            .groupBy { it.SSID }.map { (_, v) -> v.maxBy { it.level } }
            .sortedByDescending { it.level }
        val current = runCatching { wm.connectionInfo?.ssid?.trim('"') }.getOrNull()
        if (nets.isEmpty()) {
            AlertDialog.Builder(activity).setTitle("Wi-Fi").setMessage("No networks found. Move closer to the Wi-Fi and try again.")
                .setPositiveButton("Try again") { _, _ -> show(activity) }.setNegativeButton("Close", null).show()
            return
        }
        val labels = nets.map { n ->
            val open = !(n.capabilities.contains("WPA") || n.capabilities.contains("WEP") || n.capabilities.contains("SAE"))
            val bars = when { n.level > -60 -> "▂▄▆█"; n.level > -70 -> "▂▄▆"; n.level > -80 -> "▂▄"; else -> "▂" }
            "${n.SSID}${if (n.SSID == current) "  ✓ connected" else ""}${if (open) "  (open)" else ""}   $bars"
        }.toTypedArray()
        AlertDialog.Builder(activity).setTitle("Pick the Wi-Fi")
            .setItems(labels) { _, i ->
                val n = nets[i]
                val open = !(n.capabilities.contains("WPA") || n.capabilities.contains("WEP") || n.capabilities.contains("SAE"))
                if (open) connect(activity, wm, n.SSID, null, n.capabilities) else askPassword(activity, wm, n.SSID, n.capabilities)
            }
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
