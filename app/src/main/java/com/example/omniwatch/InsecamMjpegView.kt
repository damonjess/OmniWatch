package com.example.omniwatch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small native renderer for Insecam's multipart MJPEG endpoints.
 *
 * MJPEG is not HLS/MP4, so it cannot be handed to Media3 ExoPlayer. This view reads JPEG
 * start/end markers from the multipart response and draws each decoded frame on the main thread.
 */
class InsecamMjpegView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onConnected()
        fun onFrame()
        fun onError(error: Throwable)
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val stopped = AtomicBoolean(true)
    private var call: Call? = null
    private var bitmap: Bitmap? = null
    private var listener: Listener? = null

    init {
        setBackgroundColor(Color.rgb(17, 17, 17))
        setKeepScreenOn(true)
    }

    fun start(url: String, listener: Listener? = null) {
        stop()
        this.listener = listener
        stopped.set(false)
        call = client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", AppUserAgent.value)
                .header("Accept", "multipart/x-mixed-replace,image/jpeg")
                .build()
        )
        val activeCall = call ?: return
        Thread({ readFrames(activeCall) }, "insecam-mjpeg").apply {
            isDaemon = true
        }.start()
    }

    fun stop() {
        stopped.set(true)
        call?.cancel()
        call = null
        listener = null
        post {
            bitmap?.recycle()
            bitmap = null
            invalidate()
        }
    }

    private fun readFrames(activeCall: Call) {
        try {
            activeCall.execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty MJPEG response")
                listener?.let { post { it.onConnected() } }
                BufferedInputStream(body.byteStream()).use { input ->
                    val frame = ByteArrayOutputStream(256 * 1024)
                    var inFrame = false
                    var previousWasFf = false
                    while (!stopped.get()) {
                        val value = input.read()
                        if (value == -1) break
                        val byte = value and 0xff
                        if (!inFrame) {
                            if (previousWasFf && byte == 0xd8) {
                                frame.reset()
                                frame.write(0xff)
                                frame.write(0xd8)
                                inFrame = true
                                previousWasFf = false
                            } else {
                                previousWasFf = byte == 0xff
                            }
                            continue
                        }

                        frame.write(byte)
                        if (previousWasFf && byte == 0xd9) {
                            publishFrame(frame.toByteArray())
                            frame.reset()
                            inFrame = false
                            previousWasFf = false
                        } else {
                            previousWasFf = byte == 0xff
                        }
                    }
                }
            }
            if (!stopped.get()) throw IOException("MJPEG stream ended")
        } catch (error: Throwable) {
            if (!stopped.get()) {
                post { listener?.onError(error) }
            }
        }
    }

    private fun publishFrame(bytes: ByteArray) {
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
        post {
            if (stopped.get()) {
                decoded.recycle()
                return@post
            }
            bitmap?.recycle()
            bitmap = decoded
            listener?.onFrame()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = bitmap ?: return
        val scale = minOf(width.toFloat() / current.width, height.toFloat() / current.height)
        val drawWidth = current.width * scale
        val drawHeight = current.height * scale
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f
        canvas.drawBitmap(current, null, android.graphics.RectF(left, top, left + drawWidth, top + drawHeight), paint)
    }
}
