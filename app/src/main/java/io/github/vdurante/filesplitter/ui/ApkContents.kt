package io.github.vdurante.filesplitter.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.vdurante.filesplitter.apk.ApkSplitter

private const val MAX_LISTED_ENTRIES = 200

/**
 * Expandable listing of every file in the APK, largest first. Files moved into a bundle are
 * labeled with its number; the rest stayed in the slim APK.
 */
@Composable
fun ApkContents(entries: List<ApkSplitter.Entry>, bundleOf: Map<String, Int>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide APK contents" else "Show APK contents (${entries.size} files, largest first)")
        }
        if (expanded) {
            Text(
                "Files labeled with a bundle were moved into it; the rest stayed in the slim APK.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            EntryList(entries, bundleOf)
        }
    }
}

/** Rows of path + size, optionally labeled with a bundle number. Also used by the Info popup. */
@Composable
fun EntryList(entries: List<ApkSplitter.Entry>, bundleOf: Map<String, Int> = emptyMap()) {
    val context = LocalContext.current
    Column {
        entries.take(MAX_LISTED_ENTRIES).forEach { entry ->
            Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                bundleOf[entry.name]?.let { number ->
                    BundleLabel(number)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    entry.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(Formatter.formatShortFileSize(context, entry.size), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (entries.size > MAX_LISTED_ENTRIES) {
            Text("…and ${entries.size - MAX_LISTED_ENTRIES} more", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BundleLabel(number: Int) {
    Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            "bundle $number",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}
