package com.example.screenhelpai

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.*
import android.widget.TextView
import androidx.core.app.NotificationCompat

class OverlayService : Service() {
    companion object {
        const val ACTION_SHOW = "com.example.screenhelpai.SHOW"
        const val ACTION_HIDE = "com.example.screenhelpai.HIDE"
        const val ACTION_CAPTURE = "com.example.screenhelpai.CAPTURE"
    }

    private lateinit var wm: WindowManager
    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private val channelId = "screen_help_service"

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_help)
            .setContentTitle("Screen Help AI")
            .setContentText("Help bubble đang hoạt động")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(10, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(10, notification)
        }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> hideBubble()
            ACTION_SHOW -> showBubble()
            ACTION_CAPTURE -> requestCapture()
        }
        return START_STICKY
    }

    private fun requestCapture() {
        hideBubble()
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("REQUEST_CAPTURE", true)
        }
        startActivity(activityIntent)
    }

    private fun showBubble() {
        if (!Settings.canDrawOverlays(this)) return
        if (bubble != null) return

        val tv = TextView(this).apply {
            text = "HELP"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bubble_bg)
            elevation = 10f
            setOnClickListener { requestCapture() }
        }

        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            58,
            58,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 18
            y = 420
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0

        tv.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX - (event.rawX - downX).toInt()
                    params.y = startY + (event.rawY - downY).toInt()
                    runCatching { wm.updateViewLayout(v, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (kotlin.math.abs(event.rawX - downX) < 15 &&
                        kotlin.math.abs(event.rawY - downY) < 15) {
                        v.performClick()
                    }
                    true
                }
                else -> true
            }
        }

        bubble = tv
        bubbleParams = params
        runCatching { wm.addView(tv, params) }.onFailure {
            bubble = null
            bubbleParams = null
        }
    }

    private fun hideBubble() {
        bubble?.let { view -> runCatching { wm.removeView(view) } }
        bubble = null
        bubbleParams = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channelId, "Screen Help AI", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        hideBubble()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
