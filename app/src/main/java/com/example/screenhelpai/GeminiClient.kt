package com.example.screenhelpai

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
class GeminiException(
    message: String,
    val httpCode: Int = 0
) : IOException(message)

object GeminiClient {
    private const val HOST = "https://generativelanguage.googleapis.com"

    // HTTP 503 is usually a temporary capacity spike. We retry automatically and then
    // switch to another current multimodal model instead of showing a raw 503 to the user.
    // 3.1 Flash-Lite is stable and optimized for cost/throughput; the others are fallbacks.
    private val MODELS = listOf(
        "gemini-3.1-flash-lite",
        "gemini-3.5-flash",
        "gemini-3.8-flash"
    )

    // Gemini 3.x uses thinkingLevel rather than the old thinkingBudget parameter.
    private const val THINKING_LEVEL = "low"
    private const val MAX_OUTPUT_TOKENS = 8192
    private const val MAX_503_RETRIES_PER_MODEL = 2
    private const val INITIAL_RETRY_DELAY_MS = 1500L

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
        You are Screen Help AI. Read the whole screenshot you are given.
        If it contains an exercise or a question, solve it accurately, step by step, in English.
        For math problems, do not skip important transformations.
        Keep it short and easy to read on a phone.
        Number the steps as Step 1, Step 2, ... and finish with a clear line: Answer: ...
        If the image has no exercise, briefly describe the main content you can see.
        Do not invent content that is hidden or unreadable.
        Use plain text only: no Markdown, no LaTeX. Write formulas with simple symbols such as x², √, ×, ÷, ≤, ≥, ½.
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
        var last503: GeminiException? = null

        for (model in MODELS) {
            var attempt = 0
            while (true) {
                try {
                    return solveWithModel(model, apiKey, body, onPartial)
                } catch (e: GeminiException) {
                    if (e.httpCode != 503) throw e
                    last503 = e
                    if (attempt >= MAX_503_RETRIES_PER_MODEL) break

                    // 1.5s, 3s, ... between attempts.
                    delay(INITIAL_RETRY_DELAY_MS * (1L shl attempt))
                    attempt++
                }
            }
        }

        throw last503 ?: GeminiException(
            "Gemini is temporarily busy. Please try again in a moment."
        )
    }

    private suspend fun solveWithModel(
        model: String,
        apiKey: String,
        body: RequestBody,
        onPartial: (String) -> Unit
    ): String {
        val streamUrl = "$HOST/v1beta/models/$model:streamGenerateContent?alt=sse"
        val request = Request.Builder()
            .url(streamUrl)
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
                JSONObject()
                    .put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", image))
                    // Recommended by Google for image analysis; helps the model read small text.
                    .put("mediaResolution", JSONObject().put("level", "media_resolution_high"))
            )
            .put(JSONObject().put("text", "This is a screenshot. Follow the instructions."))

        val json = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", PROMPT)))
            )
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put(
                "generationConfig",
                // No "temperature": Google recommends the default (1.0) for all Gemini 3 models;
                // lowering it can cause looping or worse results on math and reasoning tasks.
                JSONObject()
                    .put("maxOutputTokens", MAX_OUTPUT_TOKENS)
                    .put("thinkingConfig", JSONObject().put("thinkingLevel", THINKING_LEVEL))
            )
        return json.toString().toRequestBody(JSON_MEDIA)
    }

    private fun readStream(response: Response, onPartial: (String) -> Unit): String {
        val body = response.body
        if (!response.isSuccessful) {
            val raw = body?.string().orEmpty()
            throw GeminiException(
                "Gemini API error ${response.code}: ${extractError(raw)}",
                response.code
            )
        }
        val source = body?.source() ?: throw GeminiException("Gemini returned no content.")

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
                throw GeminiException("Gemini API error: ${it.optString("message")}")
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
                    blockReason != null -> "Gemini refused to process this image ($blockReason)."
                    finishReason == "MAX_TOKENS" -> "Gemini hit its token limit before answering. Please try again."
                    else -> "Gemini returned no content."
                }
            )
        }
        return if (finishReason == "MAX_TOKENS") "$result\n\n(The answer was cut off because it was too long.)" else result
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
