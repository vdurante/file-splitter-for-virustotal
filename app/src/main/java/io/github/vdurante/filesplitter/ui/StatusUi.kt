package io.github.vdurante.filesplitter.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.vdurante.filesplitter.scan.ItemStatus
import io.github.vdurante.filesplitter.scan.SubmissionItem
import kotlinx.coroutines.delay

/** How a row looks: one of five states. Success is further tagged by how it was obtained. */
enum class DisplayState { WAITING, RUNNING, CLEAN, DETECTED, ERROR }

fun ItemStatus.displayState() = when (this) {
    ItemStatus.Queued, is ItemStatus.Waiting -> DisplayState.WAITING
    is ItemStatus.Packing, is ItemStatus.Hashing, ItemStatus.CheckingHash, is ItemStatus.Uploading,
    is ItemStatus.Analyzing -> DisplayState.RUNNING
    is ItemStatus.Completed ->
        if ((stats?.malicious ?: 0) + (stats?.suspicious ?: 0) > 0) DisplayState.DETECTED else DisplayState.CLEAN
    is ItemStatus.Failed -> DisplayState.ERROR
}

@Composable
fun DisplayState.color() = when (this) {
    DisplayState.WAITING -> MaterialTheme.colorScheme.onSurfaceVariant
    DisplayState.RUNNING -> MaterialTheme.colorScheme.primary
    DisplayState.CLEAN -> SafeGreen
    DisplayState.DETECTED, DisplayState.ERROR -> MaterialTheme.colorScheme.error
}

fun DisplayState.label() = when (this) {
    DisplayState.WAITING -> "Waiting"
    DisplayState.RUNNING -> "Running"
    DisplayState.CLEAN -> "Clean"
    DisplayState.DETECTED -> "Detections"
    DisplayState.ERROR -> "Error"
}

/** For the "3 clean · 1 failed" summary. */
fun DisplayState.countLabel() = when (this) {
    DisplayState.WAITING -> "waiting"
    DisplayState.RUNNING -> "running"
    DisplayState.CLEAN -> "clean"
    DisplayState.DETECTED -> "with detections"
    DisplayState.ERROR -> "failed"
}

/** "1 file", "3 files". */
fun plural(count: Int, word: String) = "$count $word" + if (count == 1) "" else "s"

/** The report page only exists once the file was found by hash or its analysis started running. */
val SubmissionItem.hasReport get() = sha256 != null && reportAvailable

/** Determinate progress for the steps that have one. */
fun ItemStatus.progress() = when (this) {
    is ItemStatus.Packing -> progress
    is ItemStatus.Hashing -> progress
    is ItemStatus.Uploading -> progress
    else -> null
}

@Composable
fun statusText(status: ItemStatus): String = when (status) {
    ItemStatus.Queued -> "Queued"
    is ItemStatus.Waiting -> {
        val seconds = rememberCountdown(status.untilMillis)
        if (seconds != null) "${status.reason} in ${seconds}s" else status.reason
    }
    is ItemStatus.Packing -> "Building bundle APK ${(status.progress * 100).toInt()}%"
    is ItemStatus.Hashing -> "Computing SHA-256 ${(status.progress * 100).toInt()}%"
    ItemStatus.CheckingHash -> "Looking up the hash on VirusTotal…"
    is ItemStatus.Uploading -> "Uploading ${(status.progress * 100).toInt()}%"
    is ItemStatus.Analyzing ->
        if (status.vtStatus == "queued") "Queued on VirusTotal, waiting for analysis to start" else "Analyzing on VirusTotal"
    is ItemStatus.Completed -> {
        val s = status.stats
        when {
            s == null -> "Analysis complete"
            s.malicious + s.suspicious > 0 -> "${s.malicious} malicious, ${s.suspicious} suspicious"
            else -> "No detections (${s.total} engines)"
        }
    }
    is ItemStatus.Failed -> status.message
}

/** Seconds left until [untilMillis], ticking once per second. */
@Composable
private fun rememberCountdown(untilMillis: Long?): Long? {
    if (untilMillis == null) return null
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(untilMillis) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    return ((untilMillis - now) / 1000).coerceAtLeast(0)
}
