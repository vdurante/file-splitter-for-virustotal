package io.github.vdurante.filesplitter.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/** Something that can be uploaded more than once (needed for retries). */
class UploadSource(val fileName: String, val size: Long, val open: () -> InputStream)

class VirusTotalException(
    val httpCode: Int,
    val errorCode: String?,
    message: String,
) : IOException(message) {
    val isQuotaExceeded get() = httpCode == 429 || errorCode == "QuotaExceededError"
    val isNotFound get() = httpCode == 404 || errorCode == "NotFoundError"
    val isBadApiKey get() = httpCode == 401 || errorCode == "WrongCredentialsError"
    val isServerError get() = httpCode >= 500
}

data class AnalysisStats(
    val malicious: Int,
    val suspicious: Int,
    val undetected: Int,
    val harmless: Int,
    val unsupported: Int,
    val timeout: Int,
    val failure: Int,
) {
    val total get() = malicious + suspicious + undetected + harmless
}

private fun JSONObject.toStats() = AnalysisStats(
    malicious = optInt("malicious"),
    suspicious = optInt("suspicious"),
    undetected = optInt("undetected"),
    harmless = optInt("harmless"),
    unsupported = optInt("type-unsupported"),
    timeout = optInt("timeout") + optInt("confirmed-timeout"),
    failure = optInt("failure"),
)

data class AnalysisResult(val status: String, val stats: AnalysisStats?)

/** VirusTotal's existing report for a file, found by hash. */
data class FileReport(val stats: AnalysisStats, val lastAnalysisMillis: Long?)

/** [apiKey] is read per request so a Retry picks up a corrected key. */
class VirusTotalClient(private val apiKey: () -> String, private val limiter: RateLimiter) {

    /** Uploads [source] and returns the analysis id. */
    suspend fun upload(
        source: UploadSource,
        onRateLimitWait: (Long) -> Unit,
        onProgress: (sent: Long, total: Long) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val target = if (source.size > DIRECT_UPLOAD_LIMIT) {
            limiter.acquire(onRateLimitWait)
            // Each upload URL is single-use, so always fetch a fresh one.
            JSONObject(request("GET", "$BASE_URL/files/upload_url")).getString("data")
        } else {
            "$BASE_URL/files"
        }
        limiter.acquire(onRateLimitWait)
        onProgress(0, source.size)

        val boundary = "----VirusTotalApk" + UUID.randomUUID().toString().replace("-", "")
        val safeName = source.fileName.replace(Regex("[\"\\r\\n\\\\]"), "_")
        val head = ("--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n").toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()

        val conn = open("POST", target).apply {
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setFixedLengthStreamingMode(head.size + source.size + tail.size)
        }
        try {
            BufferedOutputStream(conn.outputStream, BUFFER_SIZE).use { out ->
                out.write(head)
                var sent = 0L
                var lastReported = 0L
                source.open().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        sent += n
                        if (sent - lastReported >= PROGRESS_STEP) {
                            lastReported = sent
                            onProgress(sent, source.size)
                        }
                    }
                }
                if (sent != source.size) {
                    throw IOException("Read $sent bytes but expected ${source.size}")
                }
                out.write(tail)
            }
            val body = readResponse(conn)
            JSONObject(body).getJSONObject("data").getString("id")
        } finally {
            conn.disconnect()
        }
    }

    suspend fun getAnalysis(analysisId: String): AnalysisResult = withContext(Dispatchers.IO) {
        limiter.acquire()
        val json = JSONObject(request("GET", "$BASE_URL/analyses/$analysisId"))
        val attributes = json.getJSONObject("data").getJSONObject("attributes")
        AnalysisResult(attributes.optString("status"), attributes.optJSONObject("stats")?.toStats())
    }

    /**
     * Looks a file up by hash (`GET /files/{id}`). Returns null when VirusTotal doesn't know it or has
     * never finished analyzing it, in which case the file needs uploading.
     */
    suspend fun lookupFile(sha256: String, onRateLimitWait: (Long) -> Unit): FileReport? = withContext(Dispatchers.IO) {
        limiter.acquire(onRateLimitWait)
        val body = try {
            request("GET", "$BASE_URL/files/$sha256")
        } catch (e: VirusTotalException) {
            if (e.isNotFound) return@withContext null
            throw e
        }
        val attributes = JSONObject(body).getJSONObject("data").getJSONObject("attributes")
        val stats = attributes.optJSONObject("last_analysis_stats")?.toStats() ?: return@withContext null
        val date = attributes.optLong("last_analysis_date", 0L).takeIf { it > 0 }?.let { it * 1000 }
        if (date == null && stats.total == 0) return@withContext null
        FileReport(stats, date)
    }

    private fun request(method: String, url: String): String {
        val conn = open(method, url)
        try {
            return readResponse(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun open(method: String, url: String) =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 30_000
            // VirusTotal can take a while to answer after a large upload completes.
            readTimeout = 5 * 60_000
            setRequestProperty("x-apikey", apiKey())
            setRequestProperty("accept", "application/json")
        }

    private fun readResponse(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val error = runCatching { JSONObject(body).getJSONObject("error") }.getOrNull()
            throw VirusTotalException(
                httpCode = code,
                errorCode = error?.optString("code"),
                message = error?.optString("message")?.takeIf { it.isNotEmpty() }
                    ?: "HTTP $code from VirusTotal",
            )
        }
        return body
    }

    companion object {
        private const val BASE_URL = "https://www.virustotal.com/api/v3"

        /** `POST /files` accepts up to 32MB; larger files must go through `/files/upload_url`. */
        private const val DIRECT_UPLOAD_LIMIT = 32L * 1024 * 1024
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_STEP = 1024L * 1024

        fun reportUrl(sha256: String) = "https://www.virustotal.com/gui/file/$sha256"

        /**
         * SHA-256 of [source]: the id VirusTotal uses for files and report URLs, and hardware-accelerated
         * (ARMv8 crypto extensions) on modern phones, so it's not slower than MD5 or SHA-1 in practice.
         */
        suspend fun sha256(source: UploadSource, onProgress: (Float) -> Unit): String = withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            var lastReported = 0L
            source.open().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    read += n
                    if (read - lastReported >= 8 * PROGRESS_STEP) {
                        lastReported = read
                        onProgress(read.toFloat() / source.size.coerceAtLeast(1))
                    }
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
