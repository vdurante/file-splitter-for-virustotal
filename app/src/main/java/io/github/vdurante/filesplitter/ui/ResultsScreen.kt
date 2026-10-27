package io.github.vdurante.filesplitter.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.vdurante.filesplitter.R
import io.github.vdurante.filesplitter.api.VirusTotalClient
import io.github.vdurante.filesplitter.apk.ApkSplitter
import io.github.vdurante.filesplitter.scan.ItemKind
import io.github.vdurante.filesplitter.scan.ItemStatus
import io.github.vdurante.filesplitter.scan.Phase
import io.github.vdurante.filesplitter.scan.ScanSession
import io.github.vdurante.filesplitter.scan.entryKey

/** The list of submissions for the current scan, with the floating Resume / Retry failed button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(
    session: ScanSession,
    busy: Boolean,
    onRetry: (Int) -> Unit,
    onRunAgain: () -> Unit,
    onRestart: () -> Unit,
    onUpdateApiKey: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var confirmClose by remember { mutableStateOf(false) }
    var showHowItWorks by remember { mutableStateOf(false) }
    var infoFor by remember { mutableStateOf<Int?>(null) }
    BackHandler { confirmClose = true }

    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("Close this scan?") },
            text = {
                Text(
                    if (busy) "Work in progress will stop and the saved progress will be deleted."
                    else "The saved progress will be deleted."
                )
            },
            confirmButton = { TextButton(onClick = { confirmClose = false; onClose() }) { Text("Close scan") } },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Cancel") } },
        )
    }
    if (showHowItWorks) HowItWorksDialog(onDismiss = { showHowItWorks = false })
    session.items.firstOrNull { it.id == infoFor }?.let { item ->
        InfoDialog(item, session.entries, onDismiss = { infoFor = null }, onOpen = { sha -> openReport(context, sha) })
    }

    // Keep the file being worked on in view, without fighting the user while it is visible.
    val listState = rememberLazyListState()
    val headerCount = if (session.apiKeyRejected) 2 else 1
    val activeIndex = session.items.indexOfFirst { it.status.displayState() == DisplayState.RUNNING }
    LaunchedEffect(activeIndex) {
        if (activeIndex < 0) return@LaunchedEffect
        val target = activeIndex + headerCount
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == target }) listState.animateScrollToItem(target)
    }

    val queued = session.items.count { it.status == ItemStatus.Queued }
    val failed = session.items.count { (it.status as? ItemStatus.Failed)?.retryable == true }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(32.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                // An X rather than a back arrow: leaving discards the scan.
                navigationIcon = {
                    IconButton(onClick = { confirmClose = true }) { Icon(Icons.Filled.Close, "Close scan") }
                },
                actions = {
                    IconButton(onClick = { showHowItWorks = true }) { Icon(Icons.Filled.Info, "How it works") }
                },
            )
        },
        floatingActionButton = {
            // The queue runs once through the list; this reruns it for whatever isn't finished.
            if (!busy && !session.apiKeyRejected && session.phase == Phase.Ready && queued + failed > 0) {
                ExtendedFloatingActionButton(
                    onClick = onRunAgain,
                    icon = { Icon(if (queued > 0) Icons.Filled.PlayArrow else Icons.Filled.Refresh, null) },
                    text = { Text(if (queued > 0) "Resume (${queued + failed})" else "Retry failed ($failed)") },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (session.apiKeyRejected) item { ApiKeyBanner(onUpdateApiKey) }
            item { SummaryCard(session, busy, onRestart) }
            items(session.items, key = { it.id }) { item ->
                SubmissionRow(
                    item = item,
                    onOpen = { sha -> openReport(context, sha) },
                    onRetry = { onRetry(item.id) },
                    onInfo = { infoFor = item.id },
                )
            }
            if (session.entries.isNotEmpty()) {
                item { ApkContents(session.entries, bundleLabels(session)) }
            }
        }
    }
}

/** [entryKey] of each moved file → label of the bundle it went into ("bundle 1", "base · bundle 1"). */
private fun bundleLabels(session: ScanSession): Map<String, String> =
    session.items.filter { it.kind == ItemKind.BUNDLE }
        .flatMap { bundle ->
            val number = bundle.bundleNumber ?: bundle.id
            val label = bundle.innerApk?.let { "${ApkSplitter.baseName(it)} · bundle $number" } ?: "bundle $number"
            bundle.bundlePaths.map { entryKey(bundle.innerApk, it) to label }
        }
        .toMap()

@Composable
private fun HowItWorksDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How it works") },
        text = {
            Text(
                "Large files are split into multiple APKs so they can fit the 650 MB file size " +
                    "limit of VirusTotal.\n\n" +
                    "Files are checked one at a time. If VirusTotal already knows a file, its report " +
                    "is used right away; otherwise the file is uploaded. The free API allows 4 calls " +
                    "per minute, so short waits are normal.\n\n" +
                    "You can leave the app: the scan keeps running in the background.",
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

private fun openReport(context: Context, sha256: String) {
    val url = VirusTotalClient.reportUrl(sha256)
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No browser available to open $url", Toast.LENGTH_LONG).show()
    }
}
