package com.ystransportation.busflow.tablet

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ystransportation.busflow.tablet.databinding.ActivityPairBinding
import kotlinx.coroutines.launch

/** First screen: connect this tablet to BusFlow with the one-time code. Skipped once paired. */
class PairActivity : AppCompatActivity() {
    private lateinit var b: ActivityPairBinding

    override fun onResume() {
        super.onResume()
        Owner.keepLocked(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Owner.setup(this)
        if (Prefs.key(this) != null) { openRoutes(); return }
        b = ActivityPairBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Typing "abcdefgh" shows as "ABCD-EFGH"
        b.code.addTextChangedListener(object : TextWatcher {
            private var busy = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (busy || s == null) return
                val raw = s.toString().uppercase().replace(Regex("[^A-Z0-9]"), "").take(8)
                val shown = if (raw.length > 4) raw.substring(0, 4) + "-" + raw.substring(4) else raw
                if (shown != s.toString()) { busy = true; s.replace(0, s.length, shown); busy = false }
                b.error.visibility = View.GONE
            }
        })
        b.pairButton.setOnClickListener { pair() }
    }

    private fun pair() {
        val code = b.code.text.toString()
        if (code.replace("-", "").length != 8) { showError("The code has 8 letters"); return }
        b.progress.visibility = View.VISIBLE
        b.pairButton.isEnabled = false
        lifecycleScope.launch {
            try {
                val j = Api.pair(this@PairActivity, code)
                Prefs.setPaired(this@PairActivity, j.getString("key"), j.optString("name"))
                openRoutes()
            } catch (e: ApiError) {
                showError(e.message ?: "Could not connect")
            } finally {
                b.progress.visibility = View.GONE
                b.pairButton.isEnabled = true
            }
        }
    }

    private fun showError(msg: String) {
        b.error.text = msg
        b.error.visibility = View.VISIBLE
    }

    private fun openRoutes() {
        startActivity(Intent(this, SignInActivity::class.java))
        finish()
    }
}
