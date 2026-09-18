package com.lidquan.yishigame.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.StateFlow

sealed interface ScreenCaptureState {
    data object NotRequested : ScreenCaptureState
    data object PermissionDenied : ScreenCaptureState
    data object Capturing : ScreenCaptureState
    data class Captured(val width: Int, val height: Int) : ScreenCaptureState
    data class Failed(val code: String) : ScreenCaptureState
}

interface ScreenCaptureController {
    val state: StateFlow<ScreenCaptureState>
    fun permissionIntent(): Intent
    fun handlePermissionResult(resultCode: Int, data: Intent?)
}

class MediaProjectionScreenCaptureController(private val context: Context) : ScreenCaptureController {
    private val projectionManager = context.getSystemService(MediaProjectionManager::class.java)

    override val state: StateFlow<ScreenCaptureState> = MediaProjectionCaptureService.state

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
}
