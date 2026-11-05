package com.ystransportation.busflow.tablet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.ystransportation.busflow.tablet.databinding.ActivityRouteBinding
import kotlinx.coroutines.launch

/** One route: pick the day, see the stops, start (or practise) the drive. */
class RouteActivity : AppCompatActivity() {
    private lateinit var b: ActivityRouteBinding
    private lateinit var adapter: TwoLineAdapter
    private lateinit var routeId: String
    private var day = ""
    private var plan: Plan? = null

    override fun onResume() {
        super.onResume()
        Owner.keepLocked(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityRouteBinding.inflate(layoutInflater)
        setContentView(b.root)
        routeId = intent.getStringExtra("route_id") ?: run { finish(); return }
        b.title.text = intent.getStringExtra("code") ?: ""
        b.back.setOnClickListener { finish() }
        adapter = TwoLineAdapter(this)
        b.stops.adapter = adapter

        val runDays = intent.getStringArrayExtra("days")?.toList().orEmpty().ifEmpty { DAYS }
        val today = dayNameOf(todayNY())
        // Days in order starting from today, so "today" is first
        val start = DAYS.indexOf(today)
        val ordered = (0 until 7).map { DAYS[(start + it) % 7] }.filter { runDays.contains(it) }
        day = if (ordered.contains(today)) today else ordered.firstOrNull() ?: today
        ordered.forEach { d ->
            val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = if (d == today) "Today (${DAY_SHORT[d]})" else d
                isAllCaps = false
                textSize = 15f
                tag = d
                setOnClickListener { day = d; markDays(); load() }
            }
            b.days.addView(btn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = 12 })
        }
        markDays()

        b.start.setOnClickListener { go(false) }
        b.practice.setOnClickListener { go(true) }
        load()
    }

    private fun markDays() {
        for (i in 0 until b.days.childCount) {
            val v = b.days.getChildAt(i) as MaterialButton
            val on = v.tag == day
            v.backgroundTintList = ColorStateList.valueOf(if (on) ContextCompat.getColor(this, R.color.brand) else Color.TRANSPARENT)
            v.setTextColor(if (on) Color.WHITE else ContextCompat.getColor(this, R.color.ink))
        }
    }

    private fun load() {
        val date = nextDateFor(day).toString()
        b.progress.visibility = View.VISIBLE
        b.start.isEnabled = false
        b.practice.isEnabled = false
        lifecycleScope.launch {
            try {
                val (j, cached) = Api.route(this@RouteActivity, routeId, date)
                val p = Plan.from(j)
                plan = p
                adapter.rows = p.stops.map { s ->
                    Row(line1 = s.shortName, line2 = listOf(fmtTime(s.time), if (s.type != "Pickup") s.type else "").filter { it.isNotEmpty() }.joinToString(" · "), badge = s.number.toString())
                }
                val (text, ok) = when (p.pathState) {
                    "approved" -> "✓ The office set the exact roads for this route. Follow the turn-by-turn." to true
                    "stale" -> "Stops changed since the office set the roads. You'll get plain directions through the stops." to false
                    "draft" -> "The office hasn't approved the roads yet. You'll get plain directions through the stops." to false
                    else -> "No set roads for this route yet. You'll get plain directions through the stops." to false
                }
                val extra = buildString {
                    if (p.missingLocations > 0) append("\n${p.missingLocations} stop(s) have no map location and are left out.")
                    if (cached) append("\nNo signal — this is the copy saved on the tablet.")
                }
                b.status.text = text + extra
                b.status.setTextColor(ContextCompat.getColor(this@RouteActivity, if (ok) R.color.ok else R.color.warn))
                b.status.setBackgroundColor(ContextCompat.getColor(this@RouteActivity, if (ok) R.color.okBg else R.color.warnBg))
                b.status.visibility = View.VISIBLE
                val usable = p.stops.isNotEmpty()
                b.start.isEnabled = usable
                b.practice.isEnabled = usable
            } catch (e: ApiError) {
                b.status.text = e.message
                b.status.setTextColor(ContextCompat.getColor(this@RouteActivity, R.color.bad))
                b.status.setBackgroundColor(Color.TRANSPARENT)
                b.status.visibility = View.VISIBLE
                adapter.rows = emptyList()
            } finally {
                b.progress.visibility = View.GONE
            }
        }
    }

    private fun go(simulate: Boolean) {
        val p = plan ?: return
        if (!simulate && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 2)
            Toast.makeText(this, "Allow location, then tap Start again", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(this, NavActivity::class.java).putExtra("plan", p.json.toString()).putExtra("simulate", simulate))
    }
}
