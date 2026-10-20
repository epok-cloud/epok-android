package dev.epok.rum.sample

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {
    private val app get() = application as SampleApplication
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private val refresh = object : Runnable {
        override fun run() {
            status.text = app.status().toString(2)
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            setPadding(24, 48, 24, 24)
        }
        setContentView(ScrollView(this).apply { addView(layout) })
        fun button(id: Int, label: String, action: suspend () -> Unit) {
            layout.addView(Button(this).apply {
                this.id = id
                text = label
                setOnClickListener { scope.launch { withContext(Dispatchers.IO) { action() } } }
            })
        }
        button(R.id.grant_consent, "Grant consent") { app.grantConsent() }
        button(R.id.deny_consent, "Deny consent") { app.denyConsent() }
        button(R.id.withdraw_consent, "Withdraw and purge") { app.withdrawConsent() }
        layout.addView(EditText(this).apply {
            id = R.id.replay_secret_input
            hint = "Replay masking input"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            // Starts empty: the capture driver must really type the sentinel after consent.
            // SDK 3.72.3 uses identityHashCode(view) as the wireframe id. Export only
            // the id, never the value, so the driver can check this exact input node.
            File(filesDir, "replay-input.json").writeText(
                JSONObject().put("wireframeId", System.identityHashCode(this)).toString()
            )
        })
        button(R.id.call_allowed, "Call allowed loopback origin") { app.fire(true) }
        button(R.id.call_lookalike, "Call lookalike loopback origin") { app.fire(false) }
        button(R.id.go_offline, "Offline (SDK gate)") { app.setOnline(false) }
        button(R.id.go_online, "Online and drain") { app.setOnline(true) }
        button(R.id.enqueue, "Enqueue HTTP observation") { app.enqueue() }
        button(R.id.record_metric, "Record gauge") { app.rum.recordMetric("sample.trigger", 1.0) }
        button(R.id.enqueue_then_kill, "Enqueue then kill this app process") { app.enqueueThenKill() }
        button(R.id.flush_replay, "Flush replay and write status") { app.replay.flush(); app.writeStatus() }
        status = TextView(this).apply { id = R.id.delivery_status; tag = "delivery_status"; textSize = 13f }
        layout.addView(status)
        handler.post(refresh)
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        scope.cancel()
        super.onDestroy()
    }
}
