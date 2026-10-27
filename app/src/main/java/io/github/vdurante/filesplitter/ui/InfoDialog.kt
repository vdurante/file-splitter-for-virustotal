package io.github.vdurante.filesplitter.ui

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.vdurante.filesplitter.apk.ApkSplitter
import io.github.vdurante.filesplitter.scan.ItemKind
import io.github.vdurante.filesplitter.scan.ItemStatus
import io.github.vdurante.filesplitter.scan.ResolvedVia
import io.github.vdurante.filesplitter.scan.SubmissionItem
import java.text.DateFormat
import java.util.Date

/** Details for one submission. [entries] is the APK listing, used to show a bundle's contents. */
@Composable
fun InfoDialog(
    item: SubmissionItem,
    entries: List<ApkSplitter.Entry>,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val context = LocalContext.current
    fun size(bytes: Long) = "${Formatter.formatFileSize(context, bytes)} (%,d bytes)".format(bytes)
    val sha = item.sha256
    val status = item.status
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Section("Result") {
                        StatusLine(status, detailLines = 4)
                        if (status is ItemStatus.Completed) {
                            status.stats?.let { s ->
                                // Only the non-zero counts, e.g. "62 undetected · 10 unsupported · 2 timeout".
                                val counts = listOf(
                                    s.malicious to "malicious", s.suspicious to "suspicious",
                                    s.undetected to "undetected", s.harmless to "harmless",
                                    s.unsupported to "unsupported", s.timeout to "timeout", s.failure to "failed",
                                ).filter { it.first > 0 }
                                if (counts.isNotEmpty()) {
                                    Field("Engines", counts.joinToString(" · ") { (n, label) -> "$n $label" })
                                }
                            }
                            Field(
                                "Source",
                                if (status.via == ResolvedVia.HASH) "VirusTotal already had this file, nothing was uploaded"
                                else "Uploaded and scanned now",
                            )
                            status.analyzedAtMillis?.let {
                                Field("Last analyzed", DateFormat.getDateTimeInstance().format(Date(it)))
                            }
                        }
                    }

                    Section("File") {
                        Field("Type", when (item.kind) {
                            ItemKind.ORIGINAL_APK -> "Original APK (under 650 MB)"
                            ItemKind.SLIM_APK -> "Slim APK: the original minus the files moved into bundles"
                            ItemKind.BUNDLE -> "Bundle APK: AndroidManifest.xml plus files moved out of the " +
                                "original APK, at their original paths"
                        })
                        Field("Size", size(item.sizeBytes))
                        if (item.kind == ItemKind.BUNDLE) {
                            val paths = item.bundlePaths.toSet()
                            val contents = entries.filter { it.name in paths }
                            FieldLabel("Contents · ${plural(contents.size, "file")}, ${Formatter.formatFileSize(context, contents.sumOf { it.size })}")
                            EntryList(contents)
                        }
                    }

                    Section("Details", divider = false) {
                        Field("SHA-256", sha ?: "Not computed yet", code = sha != null)
                        item.analysisId?.let { Field("Analysis ID", it, code = true) }
                        Field("Upload attempts", item.uploadAttempts.toString())
                        item.lastError?.let { Field("Last error", it) }
                    }
                }
            }
        },
        confirmButton = {
            if (sha != null && item.hasReport) TextButton(onClick = { onOpen(sha) }) { Text("View on VirusTotal") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** A titled group of fields, with a divider below unless it is the last one. */
@Composable
private fun Section(title: String, divider: Boolean = true, content: @Composable () -> Unit) {
    Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
    if (divider) {
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
    }
}

/** Label above value. [code] values (hashes, ids) get a monospace, tinted box. */
@Composable
private fun Field(label: String, value: String, code: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FieldLabel(label)
        if (code) {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        } else {
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
