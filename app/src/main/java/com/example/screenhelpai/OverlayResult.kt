package com.example.screenhelpai

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.min

/**
 * The floating answer card. Main thread only.
 *
 * There is only ever one card: a new answer replaces the old one instead of stacking windows, the
 * text can be updated while it streams in, long answers scroll, and it is closed with the X button
 * (the old version cut the text at 18 lines and vanished after 20 s).
 */
object OverlayResult {
    private const val AUTO_DISMISS_MS = 120_000L

    private val handler = Handler(Looper.getMainLooper())
    private val dismissRunnable = Runnable { dismiss() }

    private var windowManager: WindowManager? = null
    private var card: View? = null
    private var body: TextView? = null

    fun show(context: Context, message: String) {
        if (card != null) {
            update(message)
            return
        }
        if (!Settings.canDrawOverlays(context)) return

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = context.resources.displayMetrics
        fun dp(value: Int) = (value * metrics.density).toInt()

        val title = TextView(context).apply {
            this.text = "Screen Help"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(125, 227, 255))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = TextView(context).apply {
            this.text = "✕"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(4), dp(4), dp(4))
            setOnClickListener { dismiss() }
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title)
            addView(close)
        }

        val textView = TextView(context).apply {
            this.text = GeminiClient.plain(message)
            textSize = 15f
            setTextColor(Color.WHITE)
            setLineSpacing(0f, 1.1f)
        }
        val scroll = MaxHeightScrollView(context, (metrics.heightPixels * 0.45f).toInt()).apply {
            addView(textView)
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.rgb(30, 34, 40))
                cornerRadius = dp(16).toFloat()
            }
            addView(header)
            addView(scroll)
        }

        val params = WindowManager.LayoutParams(
            min(metrics.widthPixels - dp(24), dp(480)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(24)
        }

        if (runCatching { wm.addView(root, params) }.isSuccess) {
            windowManager = wm
            card = root
            body = textView
            handler.postDelayed(dismissRunnable, AUTO_DISMISS_MS)
        }
    }

    fun update(message: String) {
        val view = body ?: return
        view.text = GeminiClient.plain(message)
        handler.removeCallbacks(dismissRunnable)
        handler.postDelayed(dismissRunnable, AUTO_DISMISS_MS)
    }

    fun dismiss() {
        handler.removeCallbacks(dismissRunnable)
        val view = card
        if (view != null) runCatching { windowManager?.removeView(view) }
        card = null
        body = null
        windowManager = null
    }

    /** ScrollView that never grows past [maxHeightPx], so a long answer scrolls instead of filling the screen. */
    private class MaxHeightScrollView(context: Context, private val maxHeightPx: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(
                widthMeasureSpec,
                View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST)
            )
        }
    }
}
