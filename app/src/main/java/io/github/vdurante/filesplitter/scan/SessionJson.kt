package io.github.vdurante.filesplitter.scan

import io.github.vdurante.filesplitter.api.AnalysisStats
import io.github.vdurante.filesplitter.apk.ApkSplitter
import org.json.JSONArray
import org.json.JSONObject

/**
 * Saved-state format. In-flight statuses are stored as Queued: after a restart those items simply
 * run again, and an item that was already uploaded (has an analysis id) only re-checks its result.
 */
object SessionJson {

    /** Bump when the format changes incompatibly; older saves are then dropped on restore. */
    private const val VERSION = 1

    fun write(session: ScanSession): String = JSONObject().apply {
        put("version", VERSION)
        put("uri", session.uri)
        put("fileName", session.fileName)
        put("fileSize", session.fileSize)
        put("ready", session.phase == Phase.Ready)
        put("apiKeyRejected", session.apiKeyRejected)
        put("items", JSONArray(session.items.map { it.toJson() }))
        put("entries", JSONArray(session.entries.map {
            JSONObject().put("name", it.name).put("size", it.size).put("compressedSize", it.compressedSize)
        }))
    }.toString()

    fun read(text: String): ScanSession {
        val json = JSONObject(text)
        require(json.optInt("version") == VERSION) { "Saved scan has an unknown format" }
        val ready = json.getBoolean("ready")
        return ScanSession(
            uri = json.getString("uri"),
            fileName = json.getString("fileName"),
            fileSize = json.getLong("fileSize"),
            phase = if (ready) Phase.Ready else Phase.Error("Preparing the APK was interrupted."),
            items = json.getJSONArray("items").objects().map { it.toItem() },
            apiKeyRejected = json.optBoolean("apiKeyRejected"),
            entries = json.getJSONArray("entries").objects().map {
                ApkSplitter.Entry(it.getString("name"), it.getLong("size"), it.getLong("compressedSize"))
            },
        )
    }

    private fun SubmissionItem.toJson() = JSONObject().apply {
        put("id", id)
        put("displayName", displayName)
        put("innerApk", innerApk)
        put("bundleNumber", bundleNumber)
        put("bundlePaths", JSONArray(bundlePaths))
        put("sizeBytes", sizeBytes)
        put("kind", kind.name)
        put("localPath", localPath)
        put("sha256", sha256)
        put("analysisId", analysisId)
        put("reportAvailable", reportAvailable)
        put("uploadAttempts", uploadAttempts)
        put("lastError", lastError)
        put("status", when (val s = status) {
            is ItemStatus.Completed -> JSONObject().apply {
                put("type", "completed")
                put("via", s.via.name)
                put("analyzedAt", s.analyzedAtMillis)
                s.stats?.let { put("stats", it.toJson()) }
            }
            is ItemStatus.Failed -> JSONObject()
                .put("type", "failed").put("message", s.message).put("retryable", s.retryable)
            else -> JSONObject().put("type", "queued")
        })
    }

    private fun JSONObject.toItem() = SubmissionItem(
        id = getInt("id"),
        displayName = getString("displayName"),
        innerApk = optStringOrNull("innerApk"),
        bundleNumber = if (has("bundleNumber")) getInt("bundleNumber") else null,
        bundlePaths = optJSONArray("bundlePaths")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
        sizeBytes = getLong("sizeBytes"),
        kind = ItemKind.valueOf(getString("kind")),
        localPath = optStringOrNull("localPath"),
        sha256 = optStringOrNull("sha256"),
        analysisId = optStringOrNull("analysisId"),
        reportAvailable = optBoolean("reportAvailable"),
        uploadAttempts = optInt("uploadAttempts"),
        lastError = optStringOrNull("lastError"),
        status = getJSONObject("status").let { s ->
            when (s.getString("type")) {
                "completed" -> ItemStatus.Completed(
                    stats = s.optJSONObject("stats")?.toStats(),
                    via = ResolvedVia.valueOf(s.getString("via")),
                    analyzedAtMillis = if (s.has("analyzedAt")) s.getLong("analyzedAt") else null,
                )
                "failed" -> ItemStatus.Failed(s.getString("message"), s.getBoolean("retryable"))
                else -> ItemStatus.Queued
            }
        },
    )

    private fun AnalysisStats.toJson() = JSONObject()
        .put("malicious", malicious).put("suspicious", suspicious).put("undetected", undetected)
        .put("harmless", harmless).put("unsupported", unsupported).put("timeout", timeout).put("failure", failure)

    private fun JSONObject.toStats() = AnalysisStats(
        malicious = getInt("malicious"),
        suspicious = getInt("suspicious"),
        undetected = getInt("undetected"),
        harmless = getInt("harmless"),
        unsupported = getInt("unsupported"),
        timeout = getInt("timeout"),
        failure = getInt("failure"),
    )

    private fun JSONObject.optStringOrNull(key: String) = if (isNull(key)) null else getString(key)

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
}
