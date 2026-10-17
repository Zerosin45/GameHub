package com.example.gamehub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** Floating bubble that opens a small control panel on top of the running game. */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var booster: Booster
    private val handler = Handler(Looper.getMainLooper())

    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
    private var statsView: TextView? = null

    private val ticker = object : Runnable {
        override fun run() {
            statsView?.text = statsText()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        booster = Booster(this)
        goForeground()
        showBubble()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hidePanel()
        bubble?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        bubble = null
        super.onDestroy()
    }

    // ---------- foreground notification ----------

    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Game panel", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Game Hub panel is active")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // ---------- helpers ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun overlayParams(w: Int, h: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            w,
            h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )

    private fun statsText(): String {
        val s = booster.readStats()
        val temp = String.format("%.1f", s.tempC)
        val free = String.format("%.1f", s.storageFreeGb)
        return "RAM ${s.ramUsedPct}%    BAT ${s.batteryPct}%\nTEMP $temp C    FREE $free GB"
    }

    private fun silentLabel(): String =
        if (booster.isDndOn()) "Notifications: SILENT" else "Notifications: NORMAL"

    private fun label(text: String, size: Float, color: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(Color.parseColor(color))
        return t
    }

    private fun actionButton(text: String, onClick: (TextView) -> Unit): TextView {
        val b = TextView(this)
        b.text = text
        b.textSize = 14f
        b.gravity = Gravity.CENTER
        b.setTextColor(Color.WHITE)
        b.setPadding(dp(12), dp(10), dp(12), dp(10))
        val bg = GradientDrawable()
        bg.cornerRadius = dp(14).toFloat()
        bg.setColor(Color.parseColor("#1E6BFF"))
        b.background = bg
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.topMargin = dp(8)
        b.layoutParams = lp
        b.setOnClickListener { onClick(b) }
        return b
    }

    // ---------- bubble ----------

    private fun showBubble() {
        val size = dp(48)
        val view = TextView(this)
        view.text = "GH"
        view.textSize = 14f
        view.gravity = Gravity.CENTER
        view.setTextColor(Color.WHITE)
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(Color.parseColor("#CC1E6BFF"))
        bg.setStroke(dp(2), Color.parseColor("#22E5FF"))
        view.background = bg

        val params = overlayParams(size, size)
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = dp(160)
        view.setOnTouchListener(DragListener(params, view))

        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            stopSelf()
            return
        }
        bubble = view
    }

    private inner class DragListener(
        private val params: WindowManager.LayoutParams,
        private val target: View,
    ) : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var moved = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > dp(4) || abs(dy) > dp(4)) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    wm.updateViewLayout(target, params)
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) togglePanel()
                }
            }
            return true
        }
    }

    // ---------- panel ----------

    private fun togglePanel() {
        if (panel != null) hidePanel() else showPanel()
    }

    private fun hidePanel() {
        handler.removeCallbacks(ticker)
        panel?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        panel = null
        statsView = null
    }

    private fun showPanel() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(14), dp(16), dp(14))
        val bg = GradientDrawable()
        bg.cornerRadius = dp(20).toFloat()
        bg.setColor(Color.parseColor("#F00A1530"))
        bg.setStroke(dp(2), Color.parseColor("#22E5FF"))
        box.background = bg

        box.addView(label("GAME HUB", 16f, "#22E5FF"))

        val stats = label(statsText(), 13f, "#FFFFFF")
        stats.setPadding(0, dp(8), 0, dp(4))
        box.addView(stats)
        statsView = stats

        box.addView(
            actionButton("Boost RAM") { b ->
                val n = booster.clearBackgroundApps(booster.lastGame())
                b.text = "Boosted ($n apps stopped)"
            },
        )
        box.addView(
            actionButton(silentLabel()) { b ->
                if (!booster.hasDndAccess()) {
                    b.text = "Allow DND access in Game Hub first"
                } else {
                    if (booster.isDndOn()) booster.restoreDnd() else booster.enableGameDnd()
                    b.text = silentLabel()
                }
            },
        )
        box.addView(actionButton("Hide panel") { hidePanel() })
        box.addView(
            actionButton("Stop and restore") {
                booster.restoreDnd()
                stopSelf()
            },
        )

        val params = overlayParams(dp(250), WindowManager.LayoutParams.WRAP_CONTENT)
        params.gravity = Gravity.TOP or Gravity.START
        params.x = dp(64)
        params.y = dp(60)
        try {
            wm.addView(box, params)
        } catch (e: Exception) {
            return
        }
        panel = box
        handler.postDelayed(ticker, 2000)
    }

    companion object {
        private const val CHANNEL = "overlay"
        private const val NOTIF_ID = 42

        fun start(context: Context) {
            context.startForegroundService(Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
