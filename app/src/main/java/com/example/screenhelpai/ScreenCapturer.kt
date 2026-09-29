package com.example.screenhelpai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Owns ONE MediaProjection + VirtualDisplay for the whole session.
 *
 * Why: from Android 14 the user's consent token is single-use and a MediaProjection may only call
 * createVirtualDisplay() once, so the old "new projection per tap" approach crashed. Here the
 * display is created once and only *paused* (surface = null) between captures, so an idle session
 * costs almost nothing, and each tap needs no system dialog.
 */
class ScreenCapturer(
    private val context: Context,
    private val projection: MediaProjection,
    private val onProjectionStopped: () -> Unit,
) {
    companion object {
        /** Longest side of the image sent to Gemini. Smaller = faster upload and fewer tokens. */
        const val MAX_SIDE_PX = 1920
        private const val FRAME_TIMEOUT_MS = 1_000L
    }

    private val thread = HandlerThread("screenhelp-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Mutex()

    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var width = 0
    private var height = 0

    @Volatile
    private var released = false

    @Volatile
    private var pendingFrame: CompletableDeferred<Unit>? = null

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (!released) onProjectionStopped()
        }
    }

    init {
        // Android 14+ throws in createVirtualDisplay() if no callback was registered first.
        projection.registerCallback(callback, Handler(Looper.getMainLooper()))
    }

    /** Grabs one clean frame. Heavy work runs off the main thread. */
    suspend fun capture(): Bitmap = lock.withLock {
        withContext(Dispatchers.Default) {
            check(!released) { "Capturer đã đóng." }
            val (w, h, dpi) = screenSize()

            val frame = CompletableDeferred<Unit>()
            pendingFrame = frame
            val currentReader = prepare(w, h, dpi)
            try {
                withTimeoutOrNull(FRAME_TIMEOUT_MS) { frame.await() }
                val image = currentReader.acquireLatestImage()
                    ?: throw IllegalStateException("Không chụp được màn hình. Hãy thử lại.")
                try {
                    imageToBitmap(image)
                } finally {
                    image.close()
                }
            } finally {
                pendingFrame = null
                // Pause the display so nothing is composited while we are idle.
                runCatching { display?.surface = null }
            }
        }
    }

    fun release() {
        if (released) return
        released = true
        runCatching { projection.unregisterCallback(callback) }
        runCatching { display?.release() }
        display = null
        runCatching { reader?.close() }
        reader = null
        runCatching { projection.stop() }
        thread.quitSafely()
    }

    /** Creates the display on first use, resizes it after rotation, otherwise just resumes it. */
    private fun prepare(w: Int, h: Int, dpi: Int): ImageReader {
        val existingReader = reader
        val existingDisplay = display

        if (existingReader != null && existingDisplay != null && w == width && h == height) {
            existingReader.acquireLatestImage()?.close() // drop any stale leftover frame
            existingDisplay.surface = existingReader.surface // resume
            return existingReader
        }

        val fresh = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        fresh.setOnImageAvailableListener({ pendingFrame?.complete(Unit) }, handler)

        if (existingDisplay == null) {
            display = projection.createVirtualDisplay(
                "ScreenHelpCapture",
                w,
                h,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                fresh.surface,
                null,
                handler
            )
        } else {
            existingDisplay.resize(w, h, dpi)
            existingDisplay.surface = fresh.surface
        }
        existingReader?.close()
        reader = fresh
        width = w
        height = h
        return fresh
    }

    /** Real display size (the old code used resources.displayMetrics, which can exclude the nav bar). */
    private fun screenSize(): Triple<Int, Int, Int> {
        val wm = context.getSystemService(WindowManager::class.java)
        val dpi = context.resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            Triple(bounds.width(), bounds.height(), dpi)
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Triple(dm.widthPixels, dm.heightPixels, dpi)
        }
    }

    /** Copies the frame, removing row padding and downscaling in a single draw. */
    private fun imageToBitmap(image: Image): Bitmap {
        val w = image.width
        val h = image.height
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * w

        val padded = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)

        val scale = min(1f, MAX_SIDE_PX.toFloat() / max(w, h))
        if (rowPadding == 0 && scale >= 1f) return padded

        val outW = max(1, (w * scale).roundToInt())
        val outH = max(1, (h * scale).roundToInt())
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(padded, Rect(0, 0, w, h), Rect(0, 0, outW, outH), Paint(Paint.FILTER_BITMAP_FLAG))
        padded.recycle()
        return out
    }
}

fun Bitmap.toJpeg(quality: Int = 85): ByteArray {
    val out = ByteArrayOutputStream(256 * 1024)
    compress(Bitmap.CompressFormat.JPEG, quality, out)
    return out.toByteArray()
}
