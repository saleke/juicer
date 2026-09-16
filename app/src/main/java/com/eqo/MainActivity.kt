package com.eqo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.eqo.ui.EqoTheme
import com.eqo.ui.PipelineScreen
import com.eqo.ui.PipelineViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // adb-driven smoke test:
        //   adb shell am start -n com.eqo/.MainActivity \
        //     -a com.eqo.action.SMOKE_TEST -e uri <content-or-file-uri>
        if (intent?.action == ACTION_SMOKE_TEST) {
            SmokeTestLauncher.runFromIntent(this, intent)
            return
        }

        setContent {
            EqoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val vm: PipelineViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                    androidx.compose.runtime.LaunchedEffect(intent) {
                        val uriStr = intent?.getStringExtra(EXTRA_URI) ?: intent?.data?.toString()
                        if (!uriStr.isNullOrBlank()) {
                            vm.onVideoPicked(android.net.Uri.parse(uriStr))
                        }
                    }
                    PipelineScreen(vm)
                }
            }
        }
    }

    companion object {
        const val ACTION_SMOKE_TEST = "com.eqo.action.SMOKE_TEST"
        const val EXTRA_URI = "com.eqo.extra.URI"

        /** Convenience for [SmokeTestLauncher.runFromIntent]. */
        fun smokeTestIntent(context: android.content.Context, uri: String): Intent =
            Intent(context, MainActivity::class.java).apply {
                action = ACTION_SMOKE_TEST
                putExtra(EXTRA_URI, uri)
            }
    }
}
