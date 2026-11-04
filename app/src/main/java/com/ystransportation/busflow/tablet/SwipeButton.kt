package com.ystransportation.busflow.tablet

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView

/**
 * "Swipe to clock in" bar, like the phone app: drag the round handle to the end to confirm,
 * so a clock in/out never happens by an accidental tap.
 * [reverse] = swipe from right to left (clock out).
 */
class SwipeButton @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : FrameLayout(ctx, attrs) {
    private val density = resources.displayMetrics.density
    private val label = TextView(ctx)
    private val handle = TextView(ctx)
    private var startX = 0f
    private var startTx = 0f
    var reverse = false
        set(v) { field = v; post { place(false) } }
    var onSwiped: (() -> Unit)? = null

    init {
        minimumHeight = (72 * density).toInt()
        background = GradientDrawable().apply { cornerRadius = 36 * density; setColor(Color.parseColor("#0D9688")) }
        label.apply { setTextColor(Color.WHITE); textSize = 19f; gravity = Gravity.CENTER; setTypeface(typeface, android.graphics.Typeface.BOLD) }
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val size = (64 * density).toInt()
        handle.apply {
            text = "»"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#0D9688"))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        }
        addView(handle, LayoutParams(size, size, Gravity.CENTER_VERTICAL).apply { leftMargin = (4 * density).toInt() })
        post { place(false) }
    }

    fun setText(text: String) { label.text = text }

    fun setColor(color: Int) {
        (background as GradientDrawable).setColor(color)
        handle.setTextColor(color)
    }

    private val travel get() = (width - handle.width - 8 * density).coerceAtLeast(1f)

    private fun place(animate: Boolean) {
        val tx = if (reverse) travel else 0f
        handle.text = if (reverse) "«" else "»"
        if (animate) handle.animate().translationX(tx).setDuration(180).start() else handle.translationX = tx
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { startX = event.x; startTx = handle.translationX; parent?.requestDisallowInterceptTouchEvent(true); return true }
            MotionEvent.ACTION_MOVE -> { handle.translationX = (startTx + event.x - startX).coerceIn(0f, travel); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val done = if (reverse) handle.translationX < travel * 0.12f else handle.translationX > travel * 0.88f
                place(true)
                if (done && event.actionMasked == MotionEvent.ACTION_UP) onSwiped?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.5f
    }
}
