package com.example.screenhelpai

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var keyInput: EditText
    private lateinit var overallStatus: TextView
    private lateinit var apiStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var captureStatus: TextView
    private lateinit var notifyStatus: TextView
    private var requestingCapture = false
    private var startAfterGrant = false

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            requestingCapture = false
            val data = result.data
            if (result.resultCode != Activity.RESULT_OK || data == null) {
                startAfterGrant = false
                Toast.makeText(this, "Screen Help needs the Entire screen permission to work.", Toast.LENGTH_LONG).show()
                refreshStatus()
                return@registerForActivityResult
            }
            // On Android 14+ this consent token is single-use: hand it straight to the service,
            // which creates the MediaProjection immediately and keeps it for the whole session.
            ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_START
                putExtra(OverlayService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(OverlayService.EXTRA_DATA, data)
                putExtra(OverlayService.EXTRA_SHOW_BUBBLE, startAfterGrant)
            })
            startAfterGrant = false
            // The service needs a moment to set the projection up.
            window.decorView.postDelayed({ refreshStatus() }, 500)
        }

    private val notifyLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshStatus() }

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
            setText(Prefs.apiKey(this@MainActivity))
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
            text = "Tap STATUS at the top right for details. Hold the HELP bubble for 4 seconds until it turns into X, then tap it to turn Screen Help off."
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

    private fun hasKey() = Prefs.apiKey(this).isNotBlank()

    private fun hasOverlay() = Settings.canDrawOverlays(this)

    private fun hasNotify() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Derived from the service, so it stays correct after rotation / re-opening the app. */
    private fun captureReady() = OverlayService.isCaptureActive

    private fun saveKey() {
        val key = keyInput.text.toString().trim()
        if (key.isBlank()) {
            keyInput.error = "Enter your Gemini API key"
            return
        }
        Prefs.saveApiKey(this, key)
        refreshStatus()
        Toast.makeText(this, "API key saved", Toast.LENGTH_SHORT).show()
    }

    private fun refreshStatus() {
        val keyOk = hasKey()
        val overlayOk = hasOverlay()
        val captureOk = captureReady()
        val notifyOk = hasNotify()
        setState(apiStatus, keyOk)
        setState(overlayStatus, overlayOk)
        setState(captureStatus, captureOk)
        setState(notifyStatus, notifyOk)
        val ready = keyOk && overlayOk && captureOk && notifyOk
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
        fun mark(ok: Boolean) = if (ok) "✓" else "✗"
        val text = "Gemini API key: ${mark(hasKey())}\nAppear on top: ${mark(hasOverlay())}\n" +
            "Entire screen: ${mark(captureReady())}\nNotifications: ${mark(hasNotify())}\n\n" +
            "Screen Help only runs when all required items are READY."
        AlertDialog.Builder(this).setTitle("Screen Help status").setMessage(text).setPositiveButton("OK", null).show()
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !hasNotify()) {
            notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            refreshStatus()
        }
    }

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun requestCapture() {
        if (requestingCapture || captureReady()) return
        if (!hasOverlay()) {
            openOverlaySettings()
            return
        }
        requestingCapture = true
        val manager = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            // Entire screen only. Without this the Android 14+ dialog lets the user share a single
            // app, and every screenshot taken over another app would come out blank.
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        captureLauncher.launch(intent)
    }

    private fun startScreenHelp() {
        // Use what is typed even if SAVE was not pressed.
        val typed = keyInput.text.toString().trim()
        if (typed.isNotBlank() && typed != Prefs.apiKey(this)) Prefs.saveApiKey(this, typed)

        if (!hasKey()) {
            keyInput.error = "Enter your Gemini API key first"
            keyInput.requestFocus()
            return
        }
        if (!hasOverlay()) {
            openOverlaySettings()
            return
        }
        if (!hasNotify()) {
            requestNotifications()
            return
        }
        if (!captureReady()) {
            // Ask for screen capture now; the bubble appears as soon as it is granted.
            startAfterGrant = true
            requestCapture()
            return
        }

        startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_SHOW))
        Toast.makeText(this, "Screen Help is running", Toast.LENGTH_SHORT).show()
        refreshStatus()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
