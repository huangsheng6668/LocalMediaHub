package com.juziss.localmediahub

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
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
 */
@AndroidEntryPoint
class TextReaderActivity : ComponentActivity() {

    private val viewModel: TextReaderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        setContent {
            LocalMediaHubTheme {
                LaunchedEffect(path) { viewModel.loadBook(path) }
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
