package com.example.screenhelpai

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
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

/**
 * Floating answer card.
 *
 * The answer is rendered in a WebView so Markdown and LaTeX are readable on Android.
 * MathJax renders \(...\) and \[...\] formulas. The notification still uses plain text.
 */
object OverlayResult {
    private const val AUTO_DISMISS_MS = 120_000L
    private const val MATHJAX_URL = "https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-mml-chtml.js"

    private val handler = Handler(Looper.getMainLooper())
    private val dismissRunnable = Runnable { dismiss() }

    private var windowManager: WindowManager? = null
    private var card: View? = null
    private var body: WebView? = null

    fun show(context: android.content.Context, message: String) {
        if (card != null) {
            update(message)
            return
        }
        if (!Settings.canDrawOverlays(context)) return

        val wm = context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
        val metrics = context.resources.displayMetrics
        fun dp(value: Int) = (value * metrics.density).toInt()

        val title = TextView(context).apply {
            text = "Screen Help"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(125, 227, 255))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = TextView(context).apply {
            text = "✕"
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

        val web = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.textZoom = 100
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    // MathJax is loaded asynchronously. Explicitly typeset after the page
                    // and the MathJax script are ready, so formulas are not left as raw LaTeX.
                    view.evaluateJavascript(
                        """
                        (function waitForMathJax() {
                            if (window.MathJax && window.MathJax.typesetPromise) {
                                window.MathJax.typesetPromise().catch(function(e) {});
                            } else {
                                setTimeout(waitForMathJax, 150);
                            }
                        })();
                        """.trimIndent(),
                        null
                    )
                }
            }
        }
        val maxHeight = (metrics.heightPixels * 0.55f).toInt()
        web.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            maxHeight
        )

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.rgb(30, 34, 40))
                cornerRadius = dp(16).toFloat()
            }
            addView(header)
            addView(web)
        }

        val params = WindowManager.LayoutParams(
            min(metrics.widthPixels - dp(24), dp(520)),
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
            body = web
            update(message)
        }
    }

    fun update(message: String) {
        val view = body ?: return
        view.loadDataWithBaseURL(
            "https://screenhelp.local/",
            buildHtml(message),
            "text/html",
            "UTF-8",
            null
        )
        // Re-run after a short delay because the MathJax CDN script is async.
        view.postDelayed({
            view.evaluateJavascript(
                """
                (function waitForMathJax() {
                    if (window.MathJax && window.MathJax.typesetPromise) {
                        window.MathJax.typesetPromise().catch(function(e) {});
                    } else {
                        setTimeout(waitForMathJax, 150);
                    }
                })();
                """.trimIndent(),
                null
            )
        }, 500L)
        handler.removeCallbacks(dismissRunnable)
        handler.postDelayed(dismissRunnable, AUTO_DISMISS_MS)
    }

    fun dismiss() {
        handler.removeCallbacks(dismissRunnable)
        val view = card
        if (view != null) runCatching { windowManager?.removeView(view) }
        body?.stopLoading()
        body?.destroy()
        card = null
        body = null
        windowManager = null
    }

    private fun buildHtml(markdown: String): String {
        val escaped = markdown
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

        // Keep LaTeX delimiters untouched while providing a small Markdown subset.
        var html = escaped
        html = html.replace(Regex("```([\\s\\S]*?)```"), "<pre>$1</pre>")
        html = html.replace(Regex("`([^`]+)`"), "<code>$1</code>")
        html = html.replace(Regex("\\*\\*([^*]+)\\*\\*"), "<strong>$1</strong>")
        html = html.replace(Regex("__([^_]+)__"), "<strong>$1</strong>")
        html = html.replace(Regex("(?m)^#{1,6}\\s+(.+)$"), "<h3>$1</h3>")
        html = html.replace(Regex("(?m)^\\s*[-*]\\s+(.+)$"), "• $1")
        html = html.replace(Regex("(?m)^\\s*\\d+\\.\\s+(.+)$"), "<div class=step>$1</div>")
        html = html.replace(Regex("\\n{2,}"), "<br><br>")
        html = html.replace("\n", "<br>")

        return """
            <!doctype html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <style>
                body { margin:0; padding:2px 0; background:transparent; color:#ffffff;
                       font-family: sans-serif; font-size:15px; line-height:1.55; }
                strong { color:#7de3ff; }
                h3 { color:#7de3ff; font-size:16px; margin:8px 0 5px; }
                .step { margin:4px 0; }
                pre { white-space:pre-wrap; background:#20252d; padding:8px; border-radius:8px; }
                code { color:#d9f7ff; }
                mjx-container { color:#ffffff !important; }
              </style>
              <script>
                window.MathJax = {
                  tex: { inlineMath: [['\\\\(', '\\\\)']], displayMath: [['\\\\[', '\\\\]']] },
                  svg: { fontCache: 'global' }
                };
              </script>
              <script async src="$MATHJAX_URL"></script>
            </head>
            <body>$html</body>
            </html>
        """.trimIndent()
    }
}
