package com.example.screenhelpai

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** An error message that is already safe/friendly to show to the user. */
class GeminiException(message: String) : IOException(message)

object GeminiClient {
    private const val MODEL = "gemini-2.5-flash"
    private const val HOST = "https://generativelanguage.googleapis.com"
    private const val STREAM_URL = "$HOST/v1beta/models/$MODEL:streamGenerateContent?alt=sse"

    // gemini-2.5-flash "thinks" by default and thinking tokens count against maxOutputTokens.
    // With the old 2048 limit a long think could leave no room for the visible answer.
    // 0 = no thinking (fastest), -1 = dynamic, otherwise a token budget (max 24576).
    private const val THINKING_BUDGET = 1024
    private const val MAX_OUTPUT_TOKENS = 4096

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    // One client for the whole process: connection pool + TLS session are reused between taps.
    // The default 10 s read timeout was too short for a model that thinks before answering.
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build()

    private val PROMPT = """
        Bạn là Screen Help AI. Hãy đọc toàn bộ screenshot được gửi.
        Nếu có bài tập hoặc câu hỏi, hãy giải chính xác từng bước bằng tiếng Việt.
        Với bài toán, không bỏ qua các phép biến đổi quan trọng.
        Trình bày ngắn gọn, dễ đọc trên điện thoại.
        Đánh số Bước 1, Bước 2, ... và cuối cùng ghi rõ: Đáp án: ...
        Nếu ảnh không có bài tập, nói ngắn gọn nội dung chính nhìn thấy.
        Không tự bịa nội dung bị khuất hoặc không đọc được.
        Chỉ dùng văn bản thuần: không Markdown, không LaTeX. Viết công thức bằng ký hiệu thường như x², √, ×, ÷, ≤, ≥, ½.
    """.trimIndent()

    private val boldMarks = Regex("""\*\*|__|`""")
    private val headings = Regex("""(?m)^#{1,6}\s*""")
    private val starBullets = Regex("""(?m)^\s*\*\s+""")

    /** The result is shown in a plain TextView / notification, so strip leftover Markdown. */
    fun plain(text: String): String = text
        .replace(boldMarks, "")
        .replace(headings, "")
        .replace(starBullets, "• ")
        .trim()

    /** Opens the TLS connection in the background so it is ready when the request is sent. */
    fun warmUp() {
        val request = Request.Builder().url(HOST).head().build()
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }

    /**
     * Sends the screenshot and streams the answer. [onPartial] receives the full text so far and is
     * called on an OkHttp thread. Cancelling the coroutine cancels the HTTP call.
     */
    suspend fun solve(apiKey: String, jpeg: ByteArray, onPartial: (String) -> Unit): String {
        val body = withContext(Dispatchers.Default) { buildBody(jpeg) }
        val request = Request.Builder()
            .url(STREAM_URL)
            // Header instead of ?key= so the key never ends up in URLs / logs.
            .header("x-goog-api-key", apiKey)
            .post(body)
            .build()

        return suspendCancellableCoroutine { cont ->
            val call = http.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val result = runCatching { readStream(it, onPartial) }
                        if (cont.isActive) cont.resumeWith(result)
                    }
                }
            })
        }
    }

    private fun buildBody(jpeg: ByteArray): RequestBody {
        val image = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val parts = JSONArray()
            // Gemini works best with the image first and the text after it.
            .put(
                JSONObject().put(
                    "inlineData",
                    JSONObject().put("mimeType", "image/jpeg").put("data", image)
                )
            )
            .put(JSONObject().put("text", "Đây là ảnh chụp màn hình. Hãy làm theo hướng dẫn."))

        val json = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", PROMPT)))
            )
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.2)
                    .put("maxOutputTokens", MAX_OUTPUT_TOKENS)
                    .put("thinkingConfig", JSONObject().put("thinkingBudget", THINKING_BUDGET))
            )
        return json.toString().toRequestBody(JSON_MEDIA)
    }

    private fun readStream(response: Response, onPartial: (String) -> Unit): String {
        val body = response.body
        if (!response.isSuccessful) {
            val raw = body?.string().orEmpty()
            throw GeminiException("Gemini API lỗi ${response.code}: ${extractError(raw)}")
        }
        val source = body?.source() ?: throw GeminiException("Gemini không trả về nội dung.")

        val text = StringBuilder()
        var finishReason: String? = null
        var blockReason: String? = null

        while (true) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val payload = line.substring(5).trim()
            if (payload.isEmpty()) continue
            val chunk = runCatching { JSONObject(payload) }.getOrNull() ?: continue

            chunk.optJSONObject("error")?.let {
                throw GeminiException("Gemini API lỗi: ${it.optString("message")}")
            }
            chunk.optJSONObject("promptFeedback")?.optString("blockReason")
                ?.takeIf { it.isNotBlank() }?.let { blockReason = it }

            val candidate = chunk.optJSONArray("candidates")?.optJSONObject(0) ?: continue
            candidate.optString("finishReason").takeIf { it.isNotBlank() }?.let { finishReason = it }

            val delta = extractText(candidate)
            if (delta.isNotEmpty()) {
                text.append(delta)
                onPartial(text.toString())
            }
        }

        val result = text.toString().trim()
        if (result.isEmpty()) {
            throw GeminiException(
                when {
                    blockReason != null -> "Gemini từ chối xử lý ảnh này ($blockReason)."
                    finishReason == "MAX_TOKENS" -> "Gemini hết giới hạn token trước khi trả lời. Hãy thử lại."
                    else -> "Gemini không trả về nội dung."
                }
            )
        }
        return if (finishReason == "MAX_TOKENS") "$result\n\n(Câu trả lời bị cắt do quá dài.)" else result
    }

    private fun extractText(candidate: JSONObject): String {
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (part.optBoolean("thought", false)) continue
            sb.append(part.optString("text"))
        }
        return sb.toString()
    }

    private fun extractError(raw: String): String {
        return runCatching {
            JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrDefault("").ifBlank { raw.take(220) }
    }
}
