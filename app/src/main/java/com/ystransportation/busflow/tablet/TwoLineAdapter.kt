package com.ystransportation.busflow.tablet

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

/** One list row: optional badge on the left, two lines of text, optional coloured tag on the right. */
data class Row(
    val line1: String, val line2: String = "", val badge: String? = null,
    val tag: String? = null, val tagColor: Int = Color.parseColor("#047857"), val tagBg: Int = Color.parseColor("#D1FAE5"),
    val dim: Boolean = false,
)

class TwoLineAdapter(private val ctx: Context) : BaseAdapter() {
    var rows: List<Row> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }

    override fun getCount() = rows.size
    override fun getItem(position: Int) = rows[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val v = convertView ?: LayoutInflater.from(ctx).inflate(R.layout.item_two_line, parent, false)
        val r = rows[position]
        v.findViewById<TextView>(R.id.line1).text = r.line1
        v.findViewById<TextView>(R.id.line2).apply { text = r.line2; visibility = if (r.line2.isEmpty()) View.GONE else View.VISIBLE }
        v.findViewById<TextView>(R.id.badge).apply { text = r.badge ?: ""; visibility = if (r.badge == null) View.GONE else View.VISIBLE }
        v.findViewById<TextView>(R.id.tag).apply {
            text = r.tag ?: ""
            visibility = if (r.tag == null) View.GONE else View.VISIBLE
            setTextColor(r.tagColor)
            setBackgroundColor(r.tagBg)
        }
        v.alpha = if (r.dim) 0.5f else 1f
        return v
    }
}
