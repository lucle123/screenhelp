package com.example.screenhelpai

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.text.InputType
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
    private var captureRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (intent.getBooleanExtra("REQUEST_CAPTURE", false)) {
            captureRequested = true
            requestCapture()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("REQUEST_CAPTURE", false)) {
            captureRequested = true
            requestCapture()
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 32)
        }

        val title = TextView(this).apply {
            text = "Screen Help AI"
            textSize = 28f
        }

        val info = TextView(this).apply {
            text = "Nhập Gemini API key một lần. Sau đó bật Help bubble. Khi bấm HELP, bubble sẽ tự ẩn trước khi chụp màn hình, rồi Gemini giải bài theo từng bước."
            textSize = 16f
            setPadding(0, 16, 0, 20)
        }

        keyInput = EditText(this).apply {
            hint = "Gemini API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_API, ""))
        }

        val start = Button(this).apply {
            text = "Lưu key + Bật Help"
            setOnClickListener { saveAndEnable() }
        }

        val stop = Button(this).apply {
            text = "Tắt Help bubble"
            setOnClickListener {
                stopService(Intent(this@MainActivity, OverlayService::class.java))
                Toast.makeText(this@MainActivity, "Đã tắt Help bubble", Toast.LENGTH_SHORT).show()
            }
        }

        root.addView(title)
        root.addView(info)
        root.addView(keyInput)
        root.addView(start)
        root.addView(stop)
        setContentView(root)
        requestNotifications()
    }

    private fun saveAndEnable() {
        val key = keyInput.text.toString().trim()
        if (key.isBlank()) {
            keyInput.error = "Nhập Gemini API key"
            return
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_API, key).apply()
        enableOverlay()
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        }
    }

    private fun enableOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this, "Bật quyền hiển thị trên ứng dụng khác, rồi quay lại.", Toast.LENGTH_LONG).show()
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))
        Toast.makeText(this, "Help bubble đã bật", Toast.LENGTH_SHORT).show()
    }

    private fun requestCapture() {
        val key = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_API, "").orEmpty()
        if (key.isBlank()) {
            captureRequested = false
            Toast.makeText(this, "Hãy nhập Gemini API key trước.", Toast.LENGTH_LONG).show()
            return
        }
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        captureRequested = false

        if (resultCode != Activity.RESULT_OK || data == null) {
            // Restore the bubble if the user cancelled screen capture.
            ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_SHOW
            })
            Toast.makeText(this, "Đã hủy chụp màn hình", Toast.LENGTH_SHORT).show()
            return
        }

        val key = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_API, "").orEmpty()
        val serviceIntent = Intent(this, CaptureService::class.java).apply {
            putExtra("resultCode", resultCode)
            putExtra("data", data)
            putExtra("apiKey", key)
        }
        // Move the Activity away first so the captured frame cannot contain this UI.
        moveTaskToBack(true)
        Handler(mainLooper).postDelayed({
            ContextCompat.startForegroundService(this, serviceIntent)
        }, 350)
    }
}
