package com.lidquan.yishigame.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.lidquan.yishigame.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MediaProjectionCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var closed = false
    private var frameCount = 0L
    private var lastFrameTimestampNanos = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, notificationChannelId)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this,
            notificationId,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            fail("CAPTURE_MISSING_INTENT")
            return START_NOT_STICKY
        }
        val resultCode = intent.getIntExtra(extraResultCode, ActivityResultCodeMissing)
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(extraResultData, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(extraResultData)
        }
        if (resultCode == ActivityResultCodeMissing || resultData == null) {
            fail("CAPTURE_PERMISSION_DATA_MISSING")
            return START_NOT_STICKY
        }

        if (projection != null) return START_NOT_STICKY
        closed = false
        frameCount = 0
        mutableLatestFrame.value = null
        mutableState.value = ScreenCaptureState.Capturing
        startCapture(resultCode, resultData)
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        val windowManager = getSystemService(WindowManager::class.java)
        val bounds = if (Build.VERSION.SDK_INT >= 30) windowManager.maximumWindowMetrics.bounds else null
        val metrics = resources.displayMetrics
        val width = bounds?.width()?.takeIf { it > 0 } ?: metrics.widthPixels
        val height = bounds?.height()?.takeIf { it > 0 } ?: metrics.heightPixels
        val density = metrics.densityDpi

        handlerThread = HandlerThread("screen-capture").also { it.start() }
        handler = Handler(handlerThread!!.looper)
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        val manager = getSystemService(MediaProjectionManager::class.java)
        projection = manager.getMediaProjection(resultCode, resultData).also { mediaProjection ->
            mediaProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    closeSession(ScreenCaptureState.Stopped("PROJECTION_STOPPED"))
                }
            }, handler)
        }

        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (closed || image.timestamp - lastFrameTimestampNanos < minimumFrameIntervalNanos) return@setOnImageAvailableListener
                val plane = image.planes.first()
                lastFrameTimestampNanos = image.timestamp
                frameCount += 1
                mutableLatestFrame.value = copyScreenFrame(
                    width = image.width,
                    height = image.height,
                    rowStride = plane.rowStride,
                    pixelStride = plane.pixelStride,
                    timestampNanos = image.timestamp,
                    buffer = plane.buffer,
                )
                mutableState.value = ScreenCaptureState.Active(width, height, frameCount)
            } finally {
                image.close()
            }
        }, handler)

        virtualDisplay = projection!!.createVirtualDisplay(
            "yishi-m0-single-frame",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            handler,
        )
        handler?.postDelayed({ if (mutableLatestFrame.value == null) fail("CAPTURE_TIMEOUT") }, captureTimeoutMs)
    }

    @Synchronized
    private fun fail(code: String) {
        closeSession(ScreenCaptureState.Failed(code))
    }

    @Synchronized
    private fun closeSession(finalState: ScreenCaptureState) {
        if (closed) return
        closed = true
        mutableState.value = finalState
        mutableLatestFrame.value = null
        releaseResources()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseResources() {
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        virtualDisplay = null
        imageReader = null
        projection = null
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
    }

    override fun onDestroy() {
        if (!closed) {
            closed = true
            if (mutableState.value !is ScreenCaptureState.Stopped) {
                mutableState.value = ScreenCaptureState.Stopped("SERVICE_DESTROYED")
            }
            mutableLatestFrame.value = null
            releaseResources()
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            notificationChannelId,
            getString(R.string.capture_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val extraResultCode = "capture_result_code"
        const val extraResultData = "capture_result_data"
        private const val ActivityResultCodeMissing = Int.MIN_VALUE
        private const val notificationChannelId = "screen_capture"
        private const val notificationId = 1001
        private const val captureTimeoutMs = 10_000L
        private const val minimumFrameIntervalNanos = 200_000_000L

        private val mutableState = MutableStateFlow<ScreenCaptureState>(ScreenCaptureState.NotRequested)
        val state: StateFlow<ScreenCaptureState> = mutableState.asStateFlow()
        private val mutableLatestFrame = MutableStateFlow<ScreenFrame?>(null)
        val latestFrame: StateFlow<ScreenFrame?> = mutableLatestFrame.asStateFlow()

        fun markDenied() {
            mutableLatestFrame.value = null
            mutableState.value = ScreenCaptureState.PermissionDenied
        }

        fun stop(context: Context) {
            mutableLatestFrame.value = null
            mutableState.value = ScreenCaptureState.Stopped("USER_STOPPED")
            context.stopService(Intent(context, MediaProjectionCaptureService::class.java))
        }
    }
}
