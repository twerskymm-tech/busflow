package com.ystransportation.busflow.tablet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.GridLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.ystransportation.busflow.tablet.databinding.ActivitySigninBinding
import kotlinx.coroutines.launch

/**
 * Home screen of a bus tablet. No names are shown: the driver types his driver code
 * (from BusFlow, = his Employee ID) and his PIN (last 4 digits of his phone).
 */
class SignInActivity : AppCompatActivity() {
    private lateinit var b: ActivitySigninBinding
    private var code = StringBuilder()
    private var pin = StringBuilder()
    private var onPin = false
    private var busy = false

    private var refreshJob: kotlinx.coroutines.Job? = null

    override fun onResume() {
        super.onResume()
        Owner.keepLocked(this)
        if (!::b.isInitialized) return
        // Nobody is driving now: a good time to update (and to see if the office unlocked the tablet)
        Updater.autoCheck(this, lifecycleScope)
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(5 * 60000L)
                load()
                Updater.autoCheck(this@SignInActivity, lifecycleScope)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        refreshJob?.cancel()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Someone is still signed in on this tablet: straight to his day
        if (Prefs.session(this) != null) { openDay(); return }
        b = ActivitySigninBinding.inflate(layoutInflater)
        setContentView(b.root)
        // An older version kept a list of driver names on the tablet: remove it
        runCatching { java.io.File(filesDir, "home.json").delete() }
        b.practice.setOnClickListener { startActivity(Intent(this, RoutesActivity::class.java)) }
        b.updateApp.setOnClickListener { Updater.run(this, lifecycleScope) }
        b.titleBox.setOnLongClickListener { startActivity(Intent(this, RoutesActivity::class.java)); true }
        b.codeBox.setOnClickListener { onPin = false; render() }
        b.pinBox.setOnClickListener { if (code.isNotEmpty()) { onPin = true; render() } }
        b.busTitle.text = Prefs.tabletName(this)
        buildPad()
        render()
        askPermissions()
        load()
    }

    private fun buildPad() {
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK")
        val size = (92 * resources.displayMetrics.density).toInt()
        keys.forEach { k ->
            val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = k
                textSize = if (k == "OK") 22f else 28f
                isAllCaps = false
                insetTop = 0; insetBottom = 0
                if (k == "OK") { setBackgroundColor(ContextCompat.getColor(this@SignInActivity, R.color.brand)); setTextColor(Color.WHITE) }
                setOnClickListener { press(k) }
            }
            b.pad.addView(btn, GridLayout.LayoutParams().apply { width = size; height = size; setMargins(8, 8, 8, 8) })
        }
    }

    private fun press(k: String) {
        if (busy) return
        val f = if (onPin) pin else code
        when (k) {
            "⌫" -> if (f.isNotEmpty()) f.deleteCharAt(f.length - 1) else if (onPin) onPin = false
            "OK" -> if (!onPin) { if (code.isNotEmpty()) onPin = true } else submit()
            else -> {
                if (onPin) { if (pin.length < 4) pin.append(k) } else if (code.length < 8) code.append(k)
            }
        }
        render()
        if (onPin && pin.length == 4) submit()
    }

    private fun render() {
        b.codeText.text = code.toString()
        b.pinText.text = "•".repeat(pin.length)
        val brand = ContextCompat.getColor(this, R.color.brand)
        val line = ContextCompat.getColor(this, R.color.line)
        fun box(active: Boolean) = GradientDrawable().apply {
            cornerRadius = 14 * resources.displayMetrics.density
            setColor(Color.WHITE)
            setStroke(((if (active) 3 else 2) * resources.displayMetrics.density).toInt(), if (active) brand else line)
        }
        b.codeBox.background = box(!onPin)
        b.pinBox.background = box(onPin)
    }

    private fun showMsg(text: String, bad: Boolean) {
        b.hint.text = text
        b.hint.setTextColor(ContextCompat.getColor(this, if (bad) R.color.bad else R.color.muted))
    }

    private fun submit() {
        if (busy || code.isEmpty() || pin.length < 4) return
        busy = true
        b.progress.visibility = View.VISIBLE
        showMsg("Signing in…", false)
        lifecycleScope.launch {
            try {
                val j = Api.login(this@SignInActivity, code.toString(), pin.toString())
                val drv = j.optJSONObject("driver") ?: org.json.JSONObject()
                Prefs.setSession(this@SignInActivity, j.getString("session"), drv.optString("id"), drv.optString("name"))
                openDay()
            } catch (e: ApiError) {
                if (e.unpaired) { Prefs.unpair(this@SignInActivity); startActivity(Intent(this@SignInActivity, PairActivity::class.java)); finish(); return@launch }
                showMsg(if (e.offline) "No internet. Try again in a moment." else (e.message ?: "Could not sign in"), true)
                pin.clear()
                if (!e.offline) { code.clear(); onPin = false }
                render()
            } finally {
                busy = false
                b.progress.visibility = View.GONE
            }
        }
    }

    private fun askPermissions() {
        val need = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = need.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
    }

    /** Bus name and warnings (no driver list). */
    private fun load() {
        lifecycleScope.launch {
            try {
                Api.flushQueue(this@SignInActivity)
                val (j, cached) = Api.home(this@SignInActivity)
                val bus = j.optJSONObject("bus")
                // Office unlocked this tablet for a few minutes (BusFlow → Tablets)
                val until = j.optString("unlock_until").takeIf { it.isNotEmpty() && it != "null" }?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
                if (Owner.isOwner(this@SignInActivity) && !cached) { Owner.setUnlockedUntil(this@SignInActivity, until); Owner.keepLocked(this@SignInActivity) }
                showUnlocked()
                b.busTitle.text = if (bus != null) "Bus ${bus.optString("fleet_number")}" else j.optString("tablet").ifEmpty { Prefs.tabletName(this@SignInActivity) }
                val warn = when {
                    cached -> "No signal right now. You need internet to sign in."
                    bus == null -> "This tablet isn't set to a bus yet. In BusFlow: Route Paths → Tablets → pick the bus."
                    else -> null
                }
                b.warning.text = warn ?: ""
                b.warning.visibility = if (warn == null) View.GONE else View.VISIBLE
            } catch (e: ApiError) {
                if (e.unpaired) { Prefs.unpair(this@SignInActivity); startActivity(Intent(this@SignInActivity, PairActivity::class.java)); finish() }
            }
        }
    }

    private fun showUnlocked() {
        if (!Owner.isOwner(this) || !Owner.isUnlocked(this)) { b.unlockBar.visibility = View.GONE; return }
        val t = java.time.format.DateTimeFormatter.ofPattern("h:mm a").format(java.time.Instant.ofEpochMilli(Owner.unlockedUntil(this)).atZone(NY))
        b.unlockText.text = "Unlocked for the office until $t"
        b.unlockBar.visibility = View.VISIBLE
        b.openSettings.setOnClickListener { runCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) } }
    }

    private fun openDay() {
        startActivity(Intent(this, MyDayActivity::class.java))
        finish()
    }
}
