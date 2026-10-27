package io.github.vdurante.filesplitter.scan

import io.github.vdurante.filesplitter.api.AnalysisStats
import io.github.vdurante.filesplitter.apk.ApkSplitter

enum class ItemKind { ORIGINAL_APK, SLIM_APK, BUNDLE }

/** How a finished item got its result. */
enum class ResolvedVia { UPLOAD, HASH }

sealed interface ItemStatus {
    /** Will be picked up by the next run of the queue. */
    data object Queued : ItemStatus
    /** Paused: waiting for API quota or backing off before an automatic retry. */
    data class Waiting(val reason: String, val untilMillis: Long? = null) : ItemStatus
    /** Building a bundle APK. */
    data class Packing(val progress: Float) : ItemStatus
    data class Hashing(val progress: Float) : ItemStatus
    /** Asking VirusTotal whether it already knows the hash. */
    data object CheckingHash : ItemStatus
    data class Uploading(val progress: Float) : ItemStatus
    data class Analyzing(val vtStatus: String) : ItemStatus
    data class Completed(
        val stats: AnalysisStats?,
        val via: ResolvedVia,
        /** When VirusTotal last analyzed the file (from the hash lookup). */
        val analyzedAtMillis: Long? = null,
    ) : ItemStatus
    data class Failed(val message: String, val retryable: Boolean = true) : ItemStatus
}

data class SubmissionItem(
    val id: Int,
    val displayName: String,
    /** Upload size (for a bundle, an estimate until it is built). */
    val sizeBytes: Long,
    val kind: ItemKind,
    /** Paths (inside the original APK) of the files a bundle carries. */
    val bundlePaths: List<String> = emptyList(),
    /** File on disk to upload (slim APK, or a local copy of the original). */
    val localPath: String? = null,
    val status: ItemStatus = ItemStatus.Queued,
    /** SHA-256 of what is (or would be) uploaded. */
    val sha256: String? = null,
    val analysisId: String? = null,
    /**
     * VirusTotal's report page works: the file was found by hash, or its analysis reached
     * `in-progress` (while `queued`, the page doesn't exist yet).
     */
    val reportAvailable: Boolean = false,
    val uploadAttempts: Int = 0,
    val lastError: String? = null,
)

sealed interface Phase {
    /** [progress] null means indeterminate. */
    data class Preparing(val message: String, val progress: Float? = null) : Phase
    data object Ready : Phase
    data class Error(val message: String) : Phase
}

data class ScanSession(
    val uri: String,
    val fileName: String,
    val fileSize: Long,
    val phase: Phase,
    val items: List<SubmissionItem> = emptyList(),
    /** Full APK listing, largest first. Only filled when the APK had to be split. */
    val entries: List<ApkSplitter.Entry> = emptyList(),
    /** VirusTotal rejected the API key: the queue is paused until the key is changed. */
    val apiKeyRejected: Boolean = false,
)
