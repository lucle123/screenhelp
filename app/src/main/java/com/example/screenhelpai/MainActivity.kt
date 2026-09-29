package com.example.screenhelpai

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    companion object {
        private const val REQ_CAPTURE = 1001
        private const val REQ_NOTIFY = 1002
        private const val PREFS = "screen_help"
        private const val KEY_API = "gemini_api_key"
    }

    private lateinit var keyInput: EditText
    private lateinit var overallStatus: TextView
    private lateinit var apiStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var captureStatus: TextView
    private lateinit var notifyStatus: TextView
    private var requestingCapture = false
    private var openedSettingsForOverlay = false
    private var captureReady = false
    private var screenHelpRunning = false
    private var captureData: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        // Opening or returning to the app never requests permissions automatically.
        // The user must tap the corresponding status item to request each permission.
        // MediaProjection (Entire screen) is intentionally never requested here.
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        // Returning to the app only refreshes the status indicators.
        // Never pop an Android permission/settings dialog automatically.
        refreshStatus()
        openedSettingsForOverlay = false
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(24))
            setBackgroundColor(Color.rgb(9, 14, 22))
        }

        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "✦  Screen Help"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        overallStatus = TextView(this).apply {
            text = "Status: ●"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(255, 183, 77))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.rgb(27, 35, 48))
            setOnClickListener { showStatusDialog() }
        }
        top.addView(title)
        top.addView(overallStatus)
        root.addView(top)

        val subtitle = TextView(this).apply {
            text = "AI assistant for anything on your screen"
            textSize = 14f
            setTextColor(Color.rgb(150, 165, 184))
            setPadding(0, dp(8), 0, dp(22))
        }
        root.addView(subtitle)

        keyInput = EditText(this).apply {
            hint = "Gemini API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(110, 125, 145))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.rgb(21, 29, 40))
            setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_API, ""))
        }
        root.addView(keyInput, LinearLayout.LayoutParams(-1, dp(52)))

        val save = Button(this).apply {
            text = "SAVE API KEY"
            setTextColor(Color.WHITE)
            setOnClickListener { saveKey() }
        }
        root.addView(save, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(12) })

        val start = Button(this).apply {
            text = "START SCREEN HELP"
            setTextColor(Color.WHITE)
            setOnClickListener { startScreenHelp() }
        }
        root.addView(start, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) })

        val statusTitle = TextView(this).apply {
            text = "SYSTEM STATUS"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(120, 145, 170))
            setPadding(0, dp(24), 0, dp(8))
        }
        root.addView(statusTitle)

        apiStatus = addStatusRow(root, "Gemini API key")
        overlayStatus = addStatusRow(root, "Appear on top")
        captureStatus = addStatusRow(root, "Entire screen")
        notifyStatus = addStatusRow(root, "Notifications")


        val note = TextView(this).apply {
            text = "Bấm STATUS ở góc trên phải để xem chi tiết. Giữ bong bóng HELP 7 giây để chuyển sang X và tắt Screen Help."
            textSize = 12f
            setTextColor(Color.rgb(115, 130, 150))
            setPadding(0, dp(18), 0, 0)
        }
        root.addView(note)

        setContentView(root)
    }

    private fun addStatusRow(root: LinearLayout, label: String): TextView {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(Color.rgb(16, 23, 33))
        }
        val name = TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val state = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
        }
        row.addView(name)
        row.addView(state)
        row.setOnClickListener {
            when (label) {
                "Gemini API key" -> keyInput.requestFocus()
                "Appear on top" -> openOverlaySettings()
                "Entire screen" -> requestCapture()
                "Notifications" -> requestNotifications()
            }
        }
        root.addView(row, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(5) })
        return state
    }

    private fun saveKey() {
        val key = keyInput.text.toString().trim()
        if (key.isBlank()) {
            keyInput.error = "Nhập Gemini API key"
            return
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_API, key).apply()
        refreshStatus()
        Toast.makeText(this, "Đã lưu API key", Toast.LENGTH_SHORT).show()
    }

    private fun refreshStatus() {
        val keyOk = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_API, "").orEmpty().isNotBlank()
        val overlayOk = Settings.canDrawOverlays(this)
        val notifyOk = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        setState(apiStatus, keyOk)
        setState(overlayStatus, overlayOk)
        setState(captureStatus, captureReady)
        setState(notifyStatus, notifyOk)
        val ready = keyOk && overlayOk && captureReady && notifyOk
        overallStatus.text = "Status: ●"
        overallStatus.setTextColor(if (ready) Color.rgb(72, 220, 145) else Color.rgb(255, 183, 77))
        // Do not start the overlay just because all permissions are ready.
        // The user explicitly starts Screen Help with the START button.
    }

    private fun setState(view: TextView, ok: Boolean) {
        view.text = if (ok) "READY" else "REQUIRED"
        view.setTextColor(if (ok) Color.rgb(72, 220, 145) else Color.rgb(255, 183, 77))
    }

    private fun showStatusDialog() {
        val keyOk = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_API, "").orEmpty().isNotBlank()
        val overlayOk = Settings.canDrawOverlays(this)
        val notifyOk = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val text = "Gemini API key: ${if (keyOk) "✓" else "✗"}\nAppear on top: ${if (overlayOk) "✓" else "✗"}\nEntire screen: ${if (captureReady) "✓" else "✗"}\nNotifications: ${if (notifyOk) "✓" else "✗"}\n\nScreen Help chỉ chạy khi các mục cần thiết đã READY."
        AlertDialog.Builder(this).setTitle("Screen Help status").setMessage(text).setPositiveButton("OK", null).show()
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        }
        refreshStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFY) refreshStatus()
    }

    private fun openOverlaySettings() {
        openedSettingsForOverlay = true
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun requestCapture() {
        if (requestingCapture || captureReady) return
        if (!Settings.canDrawOverlays(this)) {
            openOverlaySettings()
            return
        }
        requestingCapture = true
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        requestingCapture = false
        if (resultCode != Activity.RESULT_OK || data == null) {
            Toast.makeText(this, "Cần cho phép Entire screen để Screen Help hoạt động.", Toast.LENGTH_LONG).show()
            refreshStatus()
            return
        }
        captureReady = true
        captureData = data
        // Keep the projection token in this running app session. Do not start the
        // background overlay service until the user explicitly presses START.
        refreshStatus()
    }

    private fun startOverlay() {
        if (!screenHelpRunning || !Settings.canDrawOverlays(this) || !captureReady) return
        ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_SHOW
        })
    }

    private fun startScreenHelp() {
        val keyOk = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_API, "").orEmpty().isNotBlank()
        val overlayOk = Settings.canDrawOverlays(this)
        val notifyOk = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

        if (!keyOk) {
            keyInput.error = "Nhập Gemini API key trước"
            keyInput.requestFocus()
            return
        }
        if (!overlayOk) {
            openOverlaySettings()
            return
        }
        if (!notifyOk) {
            requestNotifications()
            return
        }
        if (!captureReady) {
            Toast.makeText(this, "Hãy cấp quyền Entire screen trước.", Toast.LENGTH_LONG).show()
            return
        }

        screenHelpRunning = true
        ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_SET_CAPTURE
            putExtra("data", captureData)
        })
        startOverlay()
        Toast.makeText(this, "Screen Help đang chạy", Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
