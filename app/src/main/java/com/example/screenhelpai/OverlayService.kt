package com.example.screenhelpai

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.TextView
import androidx.core.app.NotificationCompat

class OverlayService : Service() {
    companion object {
        const val ACTION_SHOW = "com.example.screenhelpai.SHOW"
        const val ACTION_HIDE = "com.example.screenhelpai.HIDE"
        const val ACTION_CAPTURE = "com.example.screenhelpai.CAPTURE"
        const val ACTION_SET_CAPTURE = "com.example.screenhelpai.SET_CAPTURE"
    }

    private lateinit var wm: WindowManager
    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var captureData: Intent? = null
    private val handler = Handler(Looper.getMainLooper())
    private var holdTriggered = false
    private var exitMode = false
    private val channelId = "screen_help_service"

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_help)
            .setContentTitle("Screen Help")
            .setContentText("Help bubble đang hoạt động")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(10, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(10, notification)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> hideBubble()
            ACTION_SHOW -> showBubble()
            ACTION_SET_CAPTURE -> {
                captureData = getIntentExtra(intent, "data")
                showBubble()
            }
            ACTION_CAPTURE -> requestCapture()
        }
        return START_STICKY
    }

    private fun requestCapture() {
        val data = captureData ?: return
        hideBubble()
        val captureIntent = Intent(this, CaptureService::class.java).apply {
            putExtra("resultCode", Activity.RESULT_OK)
            putExtra("data", data)
            putExtra("apiKey", getSharedPreferences("screen_help", MODE_PRIVATE).getString("gemini_api_key", "").orEmpty())
        }
        // CaptureService hides all Screen Help UI before the frame is acquired.
        if (Build.VERSION.SDK_INT >= 26) androidx.core.content.ContextCompat.startForegroundService(this, captureIntent)
        else startService(captureIntent)
    }

    @Suppress("DEPRECATION")
    private fun getIntentExtra(intent: Intent, key: String): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(key, Intent::class.java) else intent.getParcelableExtra(key)

    private fun showBubble() {
        if (!Settings.canDrawOverlays(this) || captureData == null || bubble != null) return

        val tv = TextView(this).apply {
            text = "HELP"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bubble_bg)
            elevation = 14f
        }

        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(64, 64, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 18
            y = 420
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var holdRunnable: Runnable? = null

        tv.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (exitMode) {
                        stopSelf()
                        return@setOnTouchListener true
                    }
                    holdTriggered = false
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    holdRunnable = Runnable {
                        holdTriggered = true
                        exitMode = true
                        tv.text = "X"
                        tv.setTextColor(Color.WHITE)
                    }
                    handler.postDelayed(holdRunnable!!, 7000)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.rawX - downX) > 15 || kotlin.math.abs(event.rawY - downY) > 15) {
                        holdRunnable?.let(handler::removeCallbacks)
                    }
                    params.x = startX - (event.rawX - downX).toInt()
                    params.y = startY + (event.rawY - downY).toInt()
                    runCatching { wm.updateViewLayout(v, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    holdRunnable?.let(handler::removeCallbacks)
                    if (!holdTriggered && kotlin.math.abs(event.rawX - downX) < 15 && kotlin.math.abs(event.rawY - downY) < 15) {
                        requestCapture()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    holdRunnable?.let(handler::removeCallbacks)
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
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        bubbleParams = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channelId, "Screen Help", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hideBubble()
        captureData = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
