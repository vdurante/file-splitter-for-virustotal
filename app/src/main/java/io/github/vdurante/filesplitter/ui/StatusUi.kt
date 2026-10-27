package io.github.vdurante.filesplitter.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.vdurante.filesplitter.scan.ItemStatus
import io.github.vdurante.filesplitter.scan.ResolvedVia
import io.github.vdurante.filesplitter.scan.SubmissionItem
import kotlinx.coroutines.delay

/** Broad state of a row, used for its icon, color and the summary counts. */
enum class DisplayState { WAITING, PAUSED, RUNNING, CLEAN, DETECTED, ERROR }

/** Quota waits and automatic-retry back-offs. */
private val Amber = Color(0xFFD08A1C)

fun ItemStatus.displayState() = when (this) {
    ItemStatus.Queued -> DisplayState.WAITING
    is ItemStatus.Waiting -> DisplayState.PAUSED
    is ItemStatus.Packing, is ItemStatus.Hashing, ItemStatus.CheckingHash, is ItemStatus.Uploading,
    is ItemStatus.Analyzing -> DisplayState.RUNNING
    is ItemStatus.Completed ->
        if ((stats?.malicious ?: 0) + (stats?.suspicious ?: 0) > 0) DisplayState.DETECTED else DisplayState.CLEAN
    is ItemStatus.Failed -> DisplayState.ERROR
}

@Composable
fun DisplayState.color() = when (this) {
    DisplayState.WAITING -> MaterialTheme.colorScheme.onSurfaceVariant
    DisplayState.PAUSED -> Amber
    DisplayState.RUNNING -> MaterialTheme.colorScheme.primary
    DisplayState.CLEAN -> SafeGreen
    DisplayState.DETECTED, DisplayState.ERROR -> MaterialTheme.colorScheme.error
}

fun DisplayState.label() = when (this) {
    DisplayState.WAITING -> "Waiting"
    DisplayState.PAUSED -> "Paused"
    DisplayState.RUNNING -> "Running"
    DisplayState.CLEAN -> "Clean"
    DisplayState.DETECTED -> "Detections"
    DisplayState.ERROR -> "Error"
}

/** For the "3 clean · 1 failed" summary. */
fun DisplayState.countLabel() = when (this) {
    DisplayState.WAITING -> "waiting"
    DisplayState.PAUSED -> "paused"
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

/** A row's status in one pattern: a colored [tag] plus a short [detail] beside it. */
data class StatusBadge(val tag: String, val detail: String?, val color: Color)

@Composable
fun statusBadge(status: ItemStatus): StatusBadge {
    val active = MaterialTheme.colorScheme.primary
    fun percent(progress: Float) = "${(progress * 100).toInt()}%"
    return when (status) {
        ItemStatus.Queued -> StatusBadge("WAITING", null, DisplayState.WAITING.color())
        is ItemStatus.Waiting -> {
            val seconds = rememberCountdown(status.untilMillis)
            StatusBadge("PAUSED", listOfNotNull(shortReason(status.reason), seconds?.let { "${it}s" }).joinToString(" · "), Amber)
        }
        is ItemStatus.Packing -> StatusBadge("PREPARING", "Building bundle ${percent(status.progress)}", active)
        is ItemStatus.Hashing -> StatusBadge("PREPARING", "Hashing ${percent(status.progress)}", active)
        ItemStatus.CheckingHash -> StatusBadge("PREPARING", "Checking hash", active)
        is ItemStatus.Uploading -> StatusBadge("UPLOADING", percent(status.progress), active)
        is ItemStatus.Analyzing ->
            StatusBadge(if (status.vtStatus == "queued") "ENQUEUED" else "ANALYZING", "on VirusTotal", active)
        is ItemStatus.Completed -> {
            val s = status.stats
            val detail = when {
                s == null -> "Analysis complete"
                s.malicious + s.suspicious > 0 -> "${s.malicious} malicious, ${s.suspicious} suspicious"
                else -> "No detections · ${s.total} engines"
            }
            StatusBadge(if (status.via == ResolvedVia.HASH) "HASH MATCH" else "UPLOADED", detail, status.displayState().color())
        }
        is ItemStatus.Failed -> StatusBadge("ERROR", status.message, DisplayState.ERROR.color())
    }
}

/** "Waiting for API quota" → "API quota", "Timeout. Retrying" → "Timeout". */
private fun shortReason(reason: String) = when (reason) {
    "Waiting for API quota" -> "API quota"
    else -> reason.removeSuffix(". Retrying").removeSuffix(", retrying")
}

/** [tag] detail, e.g. [UPLOADING] 42%. [detailLines] > 1 lets the Info popup show long errors. */
@Composable
fun StatusLine(status: ItemStatus, detailLines: Int = 1) {
    val badge = statusBadge(status)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(4.dp), color = badge.color.copy(alpha = 0.14f)) {
            Text(
                badge.tag,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = badge.color,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        badge.detail?.let {
            Spacer(Modifier.width(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = detailLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
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
