package com.ystransportation.busflow.tablet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ystransportation.busflow.tablet.databinding.ActivityRoutesBinding
import kotlinx.coroutines.launch

/** The list of routes. Tap one to see its stops and start driving. */
class RoutesActivity : AppCompatActivity() {
    private lateinit var b: ActivityRoutesBinding
    private lateinit var adapter: TwoLineAdapter
    private var routes: List<RouteItem> = emptyList()
    private var shown: List<RouteItem> = emptyList()
    private var todayOnly = true
    private var todayDay = ""

    override fun onResume() {
        super.onResume()
        Owner.keepLocked(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityRoutesBinding.inflate(layoutInflater)
        setContentView(b.root)
        adapter = TwoLineAdapter(this)
        b.list.adapter = adapter
        b.list.setOnItemClickListener { _, _, pos, _ ->
            val r = shown.getOrNull(pos) ?: return@setOnItemClickListener
            startActivity(Intent(this, RouteActivity::class.java).putExtra("route_id", r.id).putExtra("code", r.code).putExtra("days", r.days.toTypedArray()))
        }
        b.search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { render() }
        })
        b.filterToday.setOnClickListener { todayOnly = true; render() }
        b.filterAll.setOnClickListener { todayOnly = false; render() }
        b.refresh.setOnClickListener { load() }
        // Hidden: long-press the title for tablet settings
        b.titleBox.setOnLongClickListener { settings(); true }
        b.subtitle.text = Prefs.tabletName(this)
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
        b.message.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val (j, cached) = Api.routes(this@RoutesActivity)
                todayDay = j.optString("today_day")
                val arr = j.optJSONArray("routes")
                routes = if (arr == null) emptyList() else (0 until arr.length()).map { RouteItem.from(arr.getJSONObject(it)) }
                val name = j.optString("tablet").ifEmpty { Prefs.tabletName(this@RoutesActivity) }
                b.subtitle.text = listOf(name, todayDay).filter { it.isNotEmpty() }.joinToString(" · ")
                if (cached) message("No signal — showing the list saved on this tablet.")
                render()
            } catch (e: ApiError) {
                if (e.unpaired) { Prefs.unpair(this@RoutesActivity); startActivity(Intent(this@RoutesActivity, PairActivity::class.java)); finish(); return@launch }
                message(e.message ?: "Could not load routes")
            } finally {
                b.progress.visibility = View.GONE
            }
        }
    }

    private fun message(text: String) {
        b.message.text = text
        b.message.visibility = View.VISIBLE
    }

    private fun render() {
        val q = b.search.text.toString().uppercase().replace(Regex("[^A-Z0-9]"), "")
        shown = routes.filter { r ->
            (!todayOnly || r.runsToday) && (q.isEmpty() || (r.code + r.label + r.school).uppercase().replace(Regex("[^A-Z0-9]"), "").contains(q))
        }.sortedWith(compareBy({ !it.runsToday }, { it.code }))
        // The chosen filter is filled in, the other one is just outlined
        listOf(b.filterToday to todayOnly, b.filterAll to !todayOnly).forEach { (btn, on) ->
            btn.backgroundTintList = ColorStateList.valueOf(if (on) ContextCompat.getColor(this, R.color.brand) else Color.TRANSPARENT)
            btn.setTextColor(if (on) Color.WHITE else ContextCompat.getColor(this, R.color.ink))
        }
        adapter.rows = shown.map { r ->
            val hasToday = todayDay.isNotEmpty() && r.approvedDays.contains(todayDay)
            val any = r.approvedDays.isNotEmpty()
            Row(
                line1 = r.code + if (r.label.isNotEmpty()) "  ${r.label}" else "",
                line2 = listOf(r.direction, r.days.joinToString(", ") { DAY_SHORT[it] ?: it }).filter { it.isNotEmpty() }.joinToString(" · "),
                tag = when { hasToday -> "Path ready"; any -> "Path: other days"; else -> "No path" },
                tagColor = if (hasToday) Color.parseColor("#047857") else Color.parseColor("#64748B"),
                tagBg = if (hasToday) Color.parseColor("#D1FAE5") else Color.parseColor("#F1F5F9"),
                dim = !r.runsToday && todayOnly,
            )
        }
        if (routes.isNotEmpty() && shown.isEmpty()) message(if (todayOnly && q.isEmpty()) "No routes run today. Tap All routes." else "No route matches.")
        else if (b.message.text.startsWith("No route")) b.message.visibility = View.GONE
    }

    /** Hidden settings: server address (for testing) and disconnecting this tablet. */
    private fun settings() {
        val input = EditText(this).apply { setText(Prefs.baseUrl(this@RoutesActivity)); textSize = 14f }
        val box = LinearLayout(this).apply { setPadding(48, 16, 48, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle("Tablet settings (app ${BuildConfig.VERSION_NAME})")
            .setView(box)
            .setPositiveButton("Save server") { _, _ -> Prefs.setBaseUrl(this, input.text.toString().takeIf { it != Prefs.DEFAULT_URL }); load() }
            .setNeutralButton("Disconnect tablet") { _, _ ->
                Prefs.unpair(this)
                startActivity(Intent(this, PairActivity::class.java)); finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
