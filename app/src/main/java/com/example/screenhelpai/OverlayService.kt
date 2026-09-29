package com.example.screenhelpai

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.abs

/**
 * Foreground service (type mediaProjection) that owns the HELP bubble and the screen capture
 * session. It is started right after the user grants screen capture and keeps the projection
 * alive until it is stopped, because on Android 14+ the consent token can only be used once.
 */
class OverlayService : Service() {
    companion object {
        const val ACTION_START = "com.example.screenhelpai.START"
        const val ACTION_SHOW = "com.example.screenhelpai.SHOW"
        const val ACTION_STOP = "com.example.screenhelpai.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_SHOW_BUBBLE = "showBubble"

        private const val SERVICE_CHANNEL = "screen_help_service"
        private const val RESULT_CHANNEL = "screen_help_capture"
        private const val SERVICE_NOTIFICATION_ID = 10
        private const val RESULT_NOTIFICATION_ID = 21

        private const val BUBBLE_SIZE_DP = 56
        private const val EXIT_HOLD_MS = 7_000L
        private const val HIDE_SETTLE_MS = 100L

        /** True while a screen-capture session is alive. MainActivity reads this for its status. */
        @Volatile
        var isCaptureActive = false
            private set
    }

    private lateinit var wm: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var capturer: ScreenCapturer? = null
    private var captureJob: Job? = null

    private var bubble: TextView? = null
    private var bubbleX = 0 // distance from the right edge, remembered between show/hide
    private var bubbleY = 0
    private var exitMode = false
    private var holdTriggered = false
    private var holdRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        bubbleX = dp(6)
        bubbleY = dp(150)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when {
            intent != null && intent.action == ACTION_START -> startCapture(intent)
            intent?.action == ACTION_SHOW -> if (capturer != null) showBubble() else stopSelf()
            else -> stopSelf() // ACTION_STOP, or a stale restart without a projection
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        // Must be the very first thing: startForegroundService() requires it within seconds, and
        // on Android 14+ the mediaProjection FGS type has to be active BEFORE getMediaProjection().
        startForeground(
            SERVICE_NOTIFICATION_ID,
            buildServiceNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        if (capturer != null) {
            if (intent.getBooleanExtra(EXTRA_SHOW_BUBBLE, false)) showBubble()
            return
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = getIntentExtra(intent, EXTRA_DATA)
        if (resultCode != Activity.RESULT_OK || data == null) {
            failStart("Thiếu quyền chụp màn hình.")
            return
        }

        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val projection = manager.getMediaProjection(resultCode, data)
            capturer = ScreenCapturer(this, projection) {
                // The user (or the system) ended screen sharing.
                stopSelf()
            }
            isCaptureActive = true
            if (intent.getBooleanExtra(EXTRA_SHOW_BUBBLE, false)) showBubble()
        } catch (e: Exception) {
            failStart("Không khởi tạo được chụp màn hình: ${e.message ?: "unknown error"}")
        }
    }

    private fun failStart(message: String) {
        notifyResult(message)
        stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun getIntentExtra(intent: Intent, key: String): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(key, Intent::class.java)
        else intent.getParcelableExtra(key)

    // ---------------------------------------------------------------- capture flow

    private fun onBubbleTap() {
        val cap = capturer ?: return
        if (captureJob?.isActive == true) return // ignore taps while a request is running
        val apiKey = Prefs.apiKey(this)
        if (apiKey.isBlank()) {
            OverlayResult.show(this, "Chưa có Gemini API key. Mở Screen Help để nhập key.")
            return
        }
        GeminiClient.warmUp() // TLS handshake overlaps with the capture below
        captureJob = scope.launch { runCapture(cap, apiKey) }
    }

    private suspend fun runCapture(cap: ScreenCapturer, apiKey: String) {
        try {
            OverlayResult.dismiss() // keep the previous answer out of the new screenshot
            hideBubble(immediate = true)
            delay(HIDE_SETTLE_MS)

            val bitmap = cap.capture()
            showBubble() // the frame is already taken, so the bubble can come back right away
            OverlayResult.show(this, "Đang phân tích…")

            val jpeg = withContext(Dispatchers.Default) {
                bitmap.toJpeg().also { bitmap.recycle() }
            }
            val answer = GeminiClient.solve(apiKey, jpeg) { partial ->
                mainHandler.post { OverlayResult.update(partial) }
            }
            OverlayResult.update(answer)
            notifyResult(GeminiClient.plain(answer))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = when (e) {
                is GeminiException -> e.message.orEmpty()
                is IOException -> "Không kết nối được Gemini: ${e.message ?: "unknown error"}"
                else -> e.message ?: "Có lỗi khi chụp màn hình."
            }
            OverlayResult.show(this, message)
            notifyResult(message)
        } finally {
            showBubble() // never leave the user without a bubble
        }
    }

    // ---------------------------------------------------------------- bubble

    private fun showBubble() {
        if (bubble != null || capturer == null || !Settings.canDrawOverlays(this)) return
        exitMode = false

        val tv = TextView(this).apply {
            text = "HELP"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bubble_bg)
            elevation = 14f
        }

        val size = dp(BUBBLE_SIZE_DP) // was 64 *pixels* (~21 dp): tiny and hard to hit
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = bubbleX
            y = bubbleY
        }

        val slop = ViewConfiguration.get(this).scaledTouchSlop // was a fixed 15 px
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        tv.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (exitMode) {
                        stopSelf()
                        return@setOnTouchListener true
                    }
                    holdTriggered = false
                    dragging = false
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    val hold = Runnable {
                        holdTriggered = true
                        exitMode = true
                        tv.text = "X"
                    }
                    holdRunnable = hold
                    mainHandler.postDelayed(hold, EXIT_HOLD_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        cancelHold()
                    }
                    if (dragging) {
                        params.x = startX - dx.toInt()
                        params.y = startY + dy.toInt()
                        bubbleX = params.x
                        bubbleY = params.y
                        runCatching { wm.updateViewLayout(v, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    cancelHold()
                    if (!dragging && !holdTriggered) onBubbleTap()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelHold()
                    true
                }
                else -> true
            }
        }

        if (runCatching { wm.addView(tv, params) }.isSuccess) bubble = tv
    }

    private fun hideBubble(immediate: Boolean = false) {
        cancelHold()
        val view = bubble ?: return
        bubble = null
        runCatching { if (immediate) wm.removeViewImmediate(view) else wm.removeView(view) }
    }

    private fun cancelHold() {
        holdRunnable?.let(mainHandler::removeCallbacks)
        holdRunnable = null
    }

    // ---------------------------------------------------------------- notifications

    private fun buildServiceNotification(): Notification {
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_help)
            .setContentTitle("Screen Help")
            .setContentText("Help bubble đang hoạt động")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Tắt", stop)
            .build()
    }

    private fun notifyResult(text: String) {
        val notification = NotificationCompat.Builder(this, RESULT_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_help)
            .setContentTitle("Screen Help AI")
            .setContentText(text.replace('\n', ' ').take(180))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(RESULT_NOTIFICATION_ID, notification)
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(SERVICE_CHANNEL, "Screen Help", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(RESULT_CHANNEL, "AI results", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        isCaptureActive = false
        scope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        hideBubble()
        OverlayResult.dismiss()
        capturer?.release()
        capturer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
