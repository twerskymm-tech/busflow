package com.ystransportation.busflow.tablet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ystransportation.busflow.tablet.databinding.ActivitySigninBinding
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Home screen of a bus tablet: the driver taps his name and types his PIN (last 4 digits of his phone). */
class SignInActivity : AppCompatActivity() {
    private lateinit var b: ActivitySigninBinding
    private lateinit var adapter: TwoLineAdapter
    private var drivers: List<JSONObject> = emptyList()
    private var shown: List<JSONObject> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Someone is still signed in on this tablet: straight to his day
        if (Prefs.session(this) != null) { openDay(); return }
        b = ActivitySigninBinding.inflate(layoutInflater)
        setContentView(b.root)
        adapter = TwoLineAdapter(this)
        b.list.adapter = adapter
        b.list.setOnItemClickListener { _, _, pos, _ -> shown.getOrNull(pos)?.let { askPin(it) } }
        b.search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { render() }
        })
        b.practice.setOnClickListener { startActivity(Intent(this, RoutesActivity::class.java)) }
        b.updateApp.setOnClickListener { Updater.run(this, lifecycleScope) }
        b.titleBox.setOnLongClickListener { startActivity(Intent(this, RoutesActivity::class.java)); true }
        b.busTitle.text = Prefs.tabletName(this)
        askPermissions()
        load()
    }

    private fun askPermissions() {
        val need = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = need.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
    }

    private fun load() {
        b.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                Api.flushQueue(this@SignInActivity)
                val (j, cached) = Api.home(this@SignInActivity)
                val bus = j.optJSONObject("bus")
                b.busTitle.text = if (bus != null) "Bus ${bus.optString("fleet_number")}" else j.optString("tablet").ifEmpty { Prefs.tabletName(this@SignInActivity) }
                val warn = when {
                    cached -> "No signal — using the list saved on this tablet."
                    bus == null -> "This tablet isn't set to a bus yet. In BusFlow: Route Paths → Tablets → pick the bus."
                    else -> null
                }
                b.warning.text = warn ?: ""
                b.warning.visibility = if (warn == null) View.GONE else View.VISIBLE
                val arr = j.optJSONArray("drivers")
                drivers = if (arr == null) emptyList() else (0 until arr.length()).map { arr.getJSONObject(it) }
                render()
            } catch (e: ApiError) {
                if (e.unpaired) { Prefs.unpair(this@SignInActivity); startActivity(Intent(this@SignInActivity, PairActivity::class.java)); finish(); return@launch }
                b.message.text = e.message
                b.message.visibility = View.VISIBLE
            } finally {
                b.progress.visibility = View.GONE
            }
        }
    }

    private fun render() {
        val q = b.search.text.toString().trim().lowercase()
        shown = drivers.filter { q.isEmpty() || it.optString("name").lowercase().contains(q) }
        adapter.rows = shown.map { d ->
            Row(
                line1 = d.optString("name"),
                line2 = if (d.optBoolean("regular")) "Regular driver of this bus" else "",
                tag = if (!d.optBoolean("has_pin")) "No phone on file" else null,
                tagColor = ContextCompat.getColor(this, R.color.warn), tagBg = ContextCompat.getColor(this, R.color.warnBg),
            )
        }
    }

    private fun askPin(d: JSONObject) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
            textSize = 34f
            gravity = Gravity.CENTER
            letterSpacing = 0.5f
            hint = "• • • •"
        }
        val err = TextView(this).apply { setTextColor(ContextCompat.getColor(this@SignInActivity, R.color.bad)); textSize = 15f; gravity = Gravity.CENTER }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 24, 64, 0)
            addView(TextView(this@SignInActivity).apply { text = "Last 4 digits of your phone number"; textSize = 15f; gravity = Gravity.CENTER })
            addView(input)
            addView(err)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(d.optString("name"))
            .setView(box)
            .setNegativeButton("Cancel", null)
            .create()
        var busy = false
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (busy || s == null || s.length < 4) return
                busy = true
                err.text = "Signing in…"
                lifecycleScope.launch {
                    try {
                        val j = Api.login(this@SignInActivity, d.optString("id"), s.toString())
                        val drv = j.optJSONObject("driver") ?: JSONObject()
                        Prefs.setSession(this@SignInActivity, j.getString("session"), drv.optString("id"), drv.optString("name"))
                        dialog.dismiss()
                        openDay()
                    } catch (e: ApiError) {
                        err.text = e.message
                        input.setText("")
                    } finally {
                        busy = false
                    }
                }
            }
        })
        dialog.show()
        input.requestFocus()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    private fun openDay() {
        startActivity(Intent(this, MyDayActivity::class.java))
        finish()
    }
}
