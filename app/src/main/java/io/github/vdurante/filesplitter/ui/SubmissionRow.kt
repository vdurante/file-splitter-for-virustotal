package io.github.vdurante.filesplitter.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.vdurante.filesplitter.formatSize
import io.github.vdurante.filesplitter.scan.ItemKind
import io.github.vdurante.filesplitter.scan.ItemStatus
import io.github.vdurante.filesplitter.scan.SubmissionItem

/** One submission: status icon, name, size, status line, VirusTotal link, Retry and Info buttons. */
@Composable
fun SubmissionRow(item: SubmissionItem, onOpen: (String) -> Unit, onRetry: () -> Unit, onInfo: () -> Unit) {
    val status = item.status
    val state = status.displayState()
    // The file being worked on stands out from the rest of the list.
    val colors = if (state == DisplayState.RUNNING) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    } else {
        CardDefaults.cardColors()
    }
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onInfo), colors = colors) {
        Row(
            Modifier.padding(start = 12.dp, top = 12.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusIcon(status, state)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    formatSize(item.sizeBytes) + " · " + when (item.kind) {
                        ItemKind.ORIGINAL_APK -> "APK"
                        ItemKind.SLIM_APK -> "slim APK"
                        ItemKind.BUNDLE -> "bundle, ${plural(item.bundlePaths.size, "file")}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                StatusLine(status)
                val progress = status.progress()
                if (progress != null) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
                val sha = item.sha256
                if (sha != null && item.hasReport) {
                    Text(
                        "View on VirusTotal ↗",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onOpen(sha) }.padding(vertical = 6.dp),
                    )
                }
            }
            if ((status as? ItemStatus.Failed)?.retryable == true) {
                IconButton(onClick = onRetry) {
                    Icon(Icons.Filled.Refresh, "Retry", tint = MaterialTheme.colorScheme.error)
                }
            }
            IconButton(onClick = onInfo) { Icon(Icons.Filled.Info, "Details") }
        }
    }
}

@Composable
private fun StatusIcon(status: ItemStatus, state: DisplayState) {
    val color = state.color()
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        when (state) {
            DisplayState.WAITING, DisplayState.PAUSED -> Box(Modifier.size(16.dp).border(2.dp, color, CircleShape))
            DisplayState.RUNNING -> {
                val progress = status.progress()
                if (progress != null) {
                    CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp)
                } else {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
                }
            }
            DisplayState.CLEAN -> Icon(Icons.Filled.CheckCircle, "Clean", tint = color)
            DisplayState.DETECTED -> Icon(Icons.Filled.Warning, "Detections", tint = color)
            DisplayState.ERROR -> Icon(Icons.Filled.Clear, "Error", tint = color)
        }
    }
}
