package com.example.stackbot

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.abs

class BotService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private lateinit var handler: Handler

    // frame geometry (scaled-down capture)
    private var w = 0
    private var h = 0
    private var fullW = 0
    private var fullH = 0

    // settings
    private var lead = 0.12
    private var targetX = 0.0
    private var y0 = 0
    private var y1 = 0

    // state
    private var prev: IntArray? = null
    private var lastCx = Double.NaN
    private var lastT = 0.0
    private var cooldownUntil = 0.0

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        fullW = dm.widthPixels; fullH = dm.heightPixels
        w = 360
        h = (fullH * (w.toFloat() / fullW)).toInt()

        val p = getSharedPreferences("bot", MODE_PRIVATE)
        lead = p.getInt("lead", 120) / 1000.0
        targetX = p.getInt("target", 50) / 100.0 * w
        y0 = (h * p.getInt("bandTop", 45) / 100.0).toInt()
        y1 = (h * p.getInt("bandBot", 65) / 100.0).toInt()
        prev = null; lastCx = Double.NaN; cooldownUntil = 0.0

        thread = HandlerThread("stackbot").also { it.start() }
        handler = Handler(thread!!.looper)

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, handler)

        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        display = projection!!.createVirtualDisplay(
            "stackbot", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler
        )
        reader!!.setOnImageAvailableListener({ onFrame(it) }, handler)
        return START_NOT_STICKY
    }

    private fun onFrame(r: ImageReader) {
        val img = r.acquireLatestImage() ?: return
        try {
            val t = System.nanoTime() / 1e9
            val plane = img.planes[0]
            val buf = plane.buffer
            val rs = plane.rowStride
            val ps = plane.pixelStride
            val rows = y1 - y0
            var cur = prev
            val first = cur == null
            if (cur == null) { cur = IntArray(rows * w); prev = cur }

            var n = 0
            var sx = 0L
            for (y in y0 until y1) {
                for (x in 0 until w) {
                    val o = y * rs + x * ps
                    val g = ((buf.get(o).toInt() and 0xff) * 77 +
                            (buf.get(o + 1).toInt() and 0xff) * 150 +
                            (buf.get(o + 2).toInt() and 0xff) * 29) shr 8
                    val idx = (y - y0) * w + x
                    if (!first && abs(g - cur[idx]) > 25) { n++; sx += x }
                    cur[idx] = g
                }
            }

            // Moving block shows up as changed pixels at its leading and trailing edges,
            // so their mean x sits near the block's centre.
            if (n < 20 || t < cooldownUntil) { lastCx = Double.NaN; return }
            val cx = sx.toDouble() / n
            if (!lastCx.isNaN()) {
                val v = (cx - lastCx) / (t - lastT)   // px/s in scaled space
                if (abs(v) > 7) {
                    val eta = (targetX - cx) / v
                    if (eta > 0 && eta < 0.5) {
                        cooldownUntil = t + eta + 0.5
                        val delayMs = ((eta - lead).coerceAtLeast(0.0) * 1000).toLong()
                        handler.postDelayed({
                            TapService.instance?.tap(fullW / 2f, fullH * 0.8f)
                        }, delayMs)
                        lastCx = Double.NaN
                        return
                    }
                }
            }
            lastCx = cx; lastT = t
        } finally {
            img.close()
        }
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("bot", "Stack Bot", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "bot")
            .setContentTitle("Stack Bot is running")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    override fun onDestroy() {
        display?.release()
        reader?.close()
        projection?.stop()
        thread?.quitSafely()
        super.onDestroy()
    }
}
