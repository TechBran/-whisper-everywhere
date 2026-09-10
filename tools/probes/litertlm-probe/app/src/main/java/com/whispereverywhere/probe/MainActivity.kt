package com.whispereverywhere.probe

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var text: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        text = TextView(this).apply { textSize = 11f; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(text) })
        ProbeLog.sink = { line -> runOnUiThread { text.append(line); text.append("\n") } }
        start(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        start(intent)
    }

    private fun start(intent: Intent?) {
        val args = ProbeArgs.from(intent)
        if (args.mode == null) {
            ProbeLog.i("idle: launched without extras; drive it with am start --es mode info|litert|lm ...")
            return
        }
        thread(name = "probe-worker") { ProbeRunner(applicationContext, args).run() }
    }
}
