package com.lidquan.yishigame

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.lidquan.yishigame.ui.AssistantApp

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val capturePermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.handleCapturePermissionResult(result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AssistantApp(
                viewModel = viewModel,
                onOpenAccessibilitySettings = { startActivity(viewModel.accessibilitySettingsIntent()) },
                onRequestCapture = { capturePermission.launch(viewModel.capturePermissionIntent()) },
            )
        }
    }
}
