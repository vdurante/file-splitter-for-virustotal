package io.github.vdurante.filesplitter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.vdurante.filesplitter.ui.AppTheme
import io.github.vdurante.filesplitter.ui.HomeScreen
import io.github.vdurante.filesplitter.ui.ResultsScreen

/** Shows the home screen when there is no scan, otherwise the current scan's submissions. */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val engine = (application as FileSplitterApp).engine
        setContent {
            AppTheme {
                val session by engine.session.collectAsState()
                val current = session
                if (current == null) {
                    val apiKey by engine.apiKey.collectAsState()
                    HomeScreen(apiKey, engine::setApiKey, engine::startScan)
                } else {
                    val busy by engine.busy.collectAsState()
                    ResultsScreen(
                        current,
                        busy = busy,
                        onRetry = engine::retry,
                        onRunAgain = engine::runAgain,
                        onRestart = engine::restartPreparation,
                        onUpdateApiKey = engine::updateApiKeyAndResume,
                        onClose = engine::discard,
                    )
                }
            }
        }
    }
}
