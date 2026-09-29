package com.example.screenhelpai

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

object OverlayResult {
    fun show(context: Context, text: String) {
        if (!android.provider.Settings.canDrawOverlays(context)) return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val tv = TextView(context).apply {
            this.text = text
            textSize = 15f; setTextColor(Color.WHITE); setPadding(28,22,28,22)
            setBackgroundColor(Color.rgb(30,34,40)); maxLines = 18
            setOnClickListener { runCatching { wm.removeView(this) } }
        }
        val type = if (android.os.Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val p = WindowManager.LayoutParams(600, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity=Gravity.CENTER; y=0 }
        runCatching { wm.addView(tv,p); Handler(Looper.getMainLooper()).postDelayed({ runCatching { wm.removeView(tv) } }, 20000) }
    }
}
