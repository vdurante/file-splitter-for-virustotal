package io.github.vdurante.filesplitter.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.vdurante.filesplitter.scan.ItemKind
import io.github.vdurante.filesplitter.scan.Phase
import io.github.vdurante.filesplitter.scan.ScanSession

/** Preparation progress, a preparation error, or the overall result counts. */
@Composable
fun SummaryCard(session: ScanSession, busy: Boolean, onRestart: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp)) {
            FileHeader(session)
            Spacer(Modifier.height(16.dp))
            when (val phase = session.phase) {
                is Phase.Preparing -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(phase.message, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(12.dp))
                    if (phase.progress != null) {
                        LinearProgressIndicator(progress = { phase.progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                is Phase.Error -> {
                    Text("Couldn't prepare this file", style = MaterialTheme.typography.titleSmall)
                    Text(phase.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(onClick = onRestart) { Text("Start again") }
                }
                Phase.Ready -> ResultCounts(session, busy)
            }
        }
    }
}

/** The picked APK's name and size. */
@Composable
private fun FileHeader(session: ScanSession) {
    val context = LocalContext.current
    Text(
        session.fileName,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    if (session.fileSize >= 0) {
        Text(
            Formatter.formatFileSize(context, session.fileSize),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ResultCounts(session: ScanSession, busy: Boolean) {
    val counts = session.items.groupingBy { it.status.displayState() }.eachCount()
    val finished = (counts[DisplayState.CLEAN] ?: 0) + (counts[DisplayState.DETECTED] ?: 0)
    val bundles = session.items.count { it.kind == ItemKind.BUNDLE }
    Text("$finished of ${session.items.size} done", style = MaterialTheme.typography.titleSmall)
    Text(
        if (bundles == 0) "Submitted as a single APK" else "Slim APK + ${plural(bundles, "bundle")}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    LinearProgressIndicator(
        progress = { finished.toFloat() / session.items.size.coerceAtLeast(1) },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DisplayState.entries.filter { (counts[it] ?: 0) > 0 }.forEach { state ->
            CountChip("${counts[state]} ${state.countLabel()}", state)
        }
    }
    if (!busy && !session.apiKeyRejected && (counts[DisplayState.WAITING] ?: 0) > 0) {
        Spacer(Modifier.height(8.dp))
        Text("Paused. Tap Resume to continue.", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CountChip(text: String, state: DisplayState) {
    val color = state.color()
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.14f)) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Shown when VirusTotal rejected the key: the queue is paused until a new one is saved here. */
@Composable
fun ApiKeyBanner(onSave: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "VirusTotal rejected the API key",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                "The queue is paused. Paste a valid key to continue where it stopped.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("VirusTotal API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { onSave(key) }, enabled = key.isNotBlank(), modifier = Modifier.align(Alignment.End)) {
                Text("Save and resume")
            }
        }
    }
}
