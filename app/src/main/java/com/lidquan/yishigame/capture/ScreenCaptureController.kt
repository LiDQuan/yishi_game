package com.lidquan.yishigame.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.StateFlow

data class ScreenFrame(
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val pixelStride: Int,
    val timestampNanos: Long,
    val rgba: ByteArray,
)

sealed interface ScreenCaptureState {
    data object NotRequested : ScreenCaptureState
    data object PermissionDenied : ScreenCaptureState
    data object Capturing : ScreenCaptureState
    data class Active(val width: Int, val height: Int, val frameCount: Long) : ScreenCaptureState
    data class Stopped(val reason: String) : ScreenCaptureState
    data class Failed(val code: String) : ScreenCaptureState
}

interface ScreenCaptureController {
    val state: StateFlow<ScreenCaptureState>
    val latestFrame: StateFlow<ScreenFrame?>
    fun permissionIntent(): Intent
    fun handlePermissionResult(resultCode: Int, data: Intent?)
    fun stop()
}

class MediaProjectionScreenCaptureController(private val context: Context) : ScreenCaptureController {
    private val projectionManager = context.getSystemService(MediaProjectionManager::class.java)

    override val state: StateFlow<ScreenCaptureState> = MediaProjectionCaptureService.state
    override val latestFrame: StateFlow<ScreenFrame?> = MediaProjectionCaptureService.latestFrame

    override fun permissionIntent(): Intent = projectionManager.createScreenCaptureIntent()

    override fun handlePermissionResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            MediaProjectionCaptureService.markDenied()
            return
        }
        val intent = Intent(context, MediaProjectionCaptureService::class.java).apply {
            putExtra(MediaProjectionCaptureService.extraResultCode, resultCode)
            putExtra(MediaProjectionCaptureService.extraResultData, data)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    override fun stop() = MediaProjectionCaptureService.stop(context)
}
