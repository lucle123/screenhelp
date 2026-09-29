package com.example.screenhelpai

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class CaptureService : Service() {
    companion object {
        private const val MODEL = "gemini-2.5-flash"
        private const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient()
    private val channelId = "screen_help_capture"
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var finished = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            finishWith("Không nhận được dữ liệu chụp màn hình.")
            return START_NOT_STICKY
        }

        createChannel()
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Screen Help AI")
            .setContentText("Đang chụp và phân tích màn hình...")
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(20, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(20, notification)
        }

        val resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
        val data = getIntentExtra(intent, "data")
        val apiKey = intent.getStringExtra("apiKey").orEmpty()

        if (resultCode != Activity.RESULT_OK || data == null || apiKey.isBlank()) {
            finishWith("Thiếu quyền chụp màn hình hoặc Gemini API key.")
            return START_NOT_STICKY
        }

        capture(resultCode, data, apiKey)
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun getIntentExtra(intent: Intent, key: String): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(key, Intent::class.java)
        } else {
            intent.getParcelableExtra(key)
        }
    }

    private fun capture(resultCode: Int, data: Intent, apiKey: String) {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, data)

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        if (width <= 0 || height <= 0 || projection == null) {
            finishWith("Không xác định được kích thước màn hình.")
            return
        }

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        display = projection!!.createVirtualDisplay(
            "ScreenHelpCapture",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface,
            null,
            null
        )

        // Bubble was hidden before the system capture dialog. Give the system a moment
        // to finish the transition, then take the first complete frame.
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val image = reader?.acquireLatestImage()
                if (image == null) {
                    finishWith("Không chụp được màn hình. Hãy thử lại.")
                    return@postDelayed
                }

                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val fullWidth = width + rowPadding / pixelStride
                val bitmap = Bitmap.createBitmap(fullWidth, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()

                val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                bitmap.recycle()

                // The clean screenshot has now been captured. Restore the Help bubble
                // immediately; it cannot appear inside the already-captured bitmap.
                showBubbleAgain()
                scope.launch { solve(cropped, apiKey) }
            } catch (e: Exception) {
                finishWith("Không đọc được ảnh màn hình: ${e.message ?: "unknown error"}")
            }
        }, 600)
    }

    private suspend fun solve(bitmap: Bitmap, apiKey: String) {
        try {
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
            bitmap.recycle()
            val base64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)

            val prompt = """
                Bạn là Screen Help AI. Hãy đọc toàn bộ screenshot được gửi.
                Nếu có bài tập hoặc câu hỏi, hãy giải chính xác từng bước bằng tiếng Việt.
                Với bài toán, không bỏ qua các phép biến đổi quan trọng.
                Trình bày ngắn gọn, dễ đọc trên điện thoại.
                Đánh số Bước 1, Bước 2, ... và cuối cùng ghi rõ: Đáp án: ...
                Nếu ảnh không có bài tập, nói ngắn gọn nội dung chính nhìn thấy.
                Không tự bịa nội dung bị khuất hoặc không đọc được.
            """.trimIndent()

            val parts = JSONArray()
                .put(JSONObject().put("text", prompt))
                .put(
                    JSONObject()
                        .put("inline_data", JSONObject()
                            .put("mime_type", "image/jpeg")
                            .put("data", base64))
                )

            val contents = JSONArray()
                .put(JSONObject().put("role", "user").put("parts", parts))

            val body = JSONObject()
                .put("contents", contents)
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("temperature", 0.2)
                        .put("maxOutputTokens", 2048)
                )

            val request = Request.Builder()
                .url("$GEMINI_URL?key=$apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    finishWith("Gemini API lỗi ${response.code}: ${extractError(raw)}")
                    return
                }
                val text = extractGeminiText(JSONObject(raw))
                finishWith(if (text.isBlank()) "Gemini không trả về nội dung." else text)
            }
        } catch (e: Exception) {
            finishWith("Không kết nối được Gemini: ${e.message ?: "unknown error"}")
        }
    }

    private fun extractGeminiText(root: JSONObject): String {
        val candidates = root.optJSONArray("candidates") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until candidates.length()) {
            val candidate = candidates.optJSONObject(i) ?: continue
            val content = candidate.optJSONObject("content") ?: continue
            val parts = content.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val part = parts.optJSONObject(j) ?: continue
                val text = part.optString("text")
                if (text.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(text)
                }
            }
        }
        return sb.toString().trim()
    }

    private fun extractError(raw: String): String {
        return runCatching {
            JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrDefault("").ifBlank { raw.take(220) }
    }

    private fun showBubbleAgain() {
        ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_SHOW
        })
    }

    private fun finishWith(text: String) {
        if (finished) return
        finished = true
        Handler(Looper.getMainLooper()).post {
            showBubbleAgain()
            val manager = getSystemService(NotificationManager::class.java)
            val notification = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_menu_help)
                .setContentTitle("Screen Help AI")
                .setContentText(text.replace('\n', ' ').take(180))
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
            manager.notify(21, notification)
            OverlayResult.show(this, text)
            cleanup()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun cleanup() {
        runCatching { display?.release() }
        display = null
        runCatching { reader?.close() }
        reader = null
        runCatching { projection?.stop() }
        projection = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channelId, "AI results", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    override fun onDestroy() {
        scope.cancel()
        cleanup()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
