package com.juziss.localmediahub

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.juziss.localmediahub.ui.screen.TextReaderScreen
import com.juziss.localmediahub.ui.theme.LocalMediaHubTheme
import com.juziss.localmediahub.viewmodel.TextReaderViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * Standalone Activity hosting the text-reader Compose UI.
 *
 * Launched from the file browse screen with a server-side book `path`.
 * `launchMode="singleTop"` (declared in AndroidManifest) ensures repeated
 * launches with the same path reuse the existing task entry instead of
 * stacking identical activities.
 *
 * Path is read from [EXTRA_PATH]; [EXTRA_IS_LOCAL] is reserved for the
 * offline sidecar Task (T14) and is currently informational only.
 *
 * 沉浸模式：当 [TextReaderViewModel.readerSettings] 的 `immersiveMode` 为 true
 * 且 chrome 不可见时，隐藏系统状态栏与导航栏（下滑可临时唤出），实现真正的
 * 全屏沉浸阅读。chrome 被唤出或关闭沉浸模式后，systemBars 自动还原。
 */
@AndroidEntryPoint
class TextReaderActivity : ComponentActivity() {

    private val viewModel: TextReaderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        val isLocal = intent.getBooleanExtra(EXTRA_IS_LOCAL, false)
        setContent {
            LocalMediaHubTheme {
                LaunchedEffect(path, isLocal) { viewModel.loadBook(path, isLocal) }

                // 沉浸模式：根据 settings.immersiveMode 与 chromeVisible 切换系统 systemBars 显隐
                val settings by viewModel.readerSettings.collectAsState()
                val chromeVisible by viewModel.chromeVisible.collectAsState()
                LaunchedEffect(settings.immersiveMode, chromeVisible) {
                    val controller = WindowCompat.getInsetsController(window, window.decorView)
                    val hideSystemBars = settings.immersiveMode && !chromeVisible
                    if (hideSystemBars) {
                        controller.systemBarsBehavior =
                            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        controller.hide(WindowInsetsCompat.Type.systemBars())
                    } else {
                        controller.show(WindowInsetsCompat.Type.systemBars())
                    }
                }

                TextReaderScreen(viewModel = viewModel, onBack = { finish() })
            }
        }
    }

    companion object {
        const val EXTRA_PATH = "extra_path"
        const val EXTRA_IS_LOCAL = "extra_is_local"

        /** Factory that assembles a launch Intent for [TextReaderActivity]. */
        fun newIntent(ctx: Context, path: String, isLocal: Boolean = false): Intent =
            Intent(ctx, TextReaderActivity::class.java)
                .putExtra(EXTRA_PATH, path)
                .putExtra(EXTRA_IS_LOCAL, isLocal)
    }
}
