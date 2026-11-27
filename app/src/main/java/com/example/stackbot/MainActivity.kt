package com.example.stackbot

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.*

class MainActivity : Activity() {
    private val REQ = 1
    private val fields = LinkedHashMap<String, EditText>()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val prefs = getSharedPreferences("bot", MODE_PRIVATE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 48, 48, 48) }

        fun add(label: String, key: String, def: Int) {
            root.addView(TextView(this).apply { text = label })
            val e = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(prefs.getInt(key, def).toString())
            }
            fields[key] = e
            root.addView(e)
        }
        add("Lead time (ms): raise if taps land late", "lead", 120)
        add("Target X (% of screen width)", "target", 50)
        add("Band top (% of height)", "bandTop", 45)
        add("Band bottom (% of height)", "bandBot", 65)

        root.addView(Button(this).apply {
            text = "1. Enable tap service (Accessibility)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "2. Start bot"
            setOnClickListener { start() }
        })
        root.addView(Button(this).apply {
            text = "Stop bot"
            setOnClickListener { stopService(Intent(this@MainActivity, BotService::class.java)) }
        })
        root.addView(TextView(this).apply {
            text = "After starting, switch to Stack and begin a run. Tap Stop bot (or the notification) when done."
            setPadding(0, 32, 0, 0)
        })
        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 0)
    }

    private fun start() {
        val ed = getSharedPreferences("bot", MODE_PRIVATE).edit()
        fields.forEach { (k, v) -> ed.putInt(k, v.text.toString().toIntOrNull() ?: 0) }
        ed.apply()
        if (TapService.instance == null) {
            Toast.makeText(this, "Enable the tap service in Accessibility first", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, BotService::class.java).putExtra("code", resultCode).putExtra("data", data)
            startForegroundService(i)
            moveTaskToBack(true)
        }
    }
}
