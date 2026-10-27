package io.github.vdurante.filesplitter.scan

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import io.github.vdurante.filesplitter.api.RateLimiter
import io.github.vdurante.filesplitter.api.UploadSource
import io.github.vdurante.filesplitter.api.VirusTotalClient
import io.github.vdurante.filesplitter.api.VirusTotalException
import io.github.vdurante.filesplitter.apk.ApkSplitter
import io.github.vdurante.filesplitter.apk.ZipReader
import io.github.vdurante.filesplitter.formatSize
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.ZipException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the current scan independently of any screen, so work continues while [ScanService] keeps
 * the process alive in the background. State is saved to disk after every change and restored on
 * the next launch.
 */
class ScanEngine(private val app: Application) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _apiKey = MutableStateFlow(prefs.getString(KEY_API_KEY, "").orEmpty())
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _session = MutableStateFlow<ScanSession?>(null)
    val session: StateFlow<ScanSession?> = _session.asStateFlow()

    /** True while the APK is being prepared or the queue is running. Drives [ScanService]. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val limiter = RateLimiter()
    private val client = VirusTotalClient({ _apiKey.value.trim() }, limiter)

    private val dir = File(app.noBackupFilesDir, "scan")
    private val stateFile = File(dir, "state.json")
    private val saveSignal = Channel<Unit>(Channel.CONFLATED)

    private var scan: Scan? = null

    init {
        scope.launch(Dispatchers.IO) {
            for (signal in saveSignal) {
                delay(500)
                save()
            }
        }
        restore()
    }

    fun setApiKey(value: String) {
        _apiKey.value = value
        prefs.edit { putString(KEY_API_KEY, value.trim()) }
    }

    fun startScan(uri: Uri) {
        discard()
        // Keep read access across restarts so saved progress can be resumed.
        runCatching {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val (name, size) = queryNameAndSize(uri)
        _session.value = ScanSession(uri.toString(), name, size, Phase.Preparing("Opening APK…"))
        // Assign before preparing: Scan ignores state updates unless it is the current scan.
        val newScan = Scan(uri)
        scan = newScan
        newScan.prepare()
    }

    /** Prepares the same APK again after an interrupted or failed preparation. */
    fun restartPreparation() {
        val uri = _session.value?.uri ?: return
        startScan(uri.toUri())
    }

    fun retry(id: Int) {
        scan?.requeue { it.id == id }
    }

    /** Re-runs the queue: finished files are skipped, queued and failed ones run again. */
    fun runAgain() {
        scan?.requeue { true }
    }

    /** Saves a corrected API key and resumes the queue that a rejected key paused. */
    fun updateApiKeyAndResume(value: String) {
        setApiKey(value)
        scan?.requeue { true }
    }

    /** Stops in-flight work but keeps progress (used when Android ends the background service). */
    fun pause(reason: String) {
        scan?.pause(reason)
    }

    /** Cancels any work and deletes the saved scan. */
    fun discard() {
        scan?.close()
        scan = null
        _session.value = null
        _busy.value = false
        // Synchronous (unlinking is fast) so it can't race with the files of a scan started right after.
        dir.deleteRecursively()
    }

    private fun restore() {
        val saved = runCatching { SessionJson.read(stateFile.readText()) }
            .onFailure { if (stateFile.exists()) Log.w(TAG, "Couldn't restore saved scan", it) }
            .getOrNull() ?: return
        _session.value = saved
        if (saved.phase == Phase.Ready) scan = Scan(saved.uri.toUri())
    }

    private fun save() {
        val current = _session.value ?: return
        dir.mkdirs()
        val tmp = File(dir, "state.json.tmp")
        tmp.writeText(SessionJson.write(current))
        tmp.renameTo(stateFile)
    }

    private fun queryNameAndSize(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "app.apk"
        var size = -1L
        app.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        return name.replace('/', '_') to size
    }

    /** One picked APK: owns its coroutines and open zip so it can be torn down cleanly. */
    private inner class Scan(private val uri: Uri) {
        private val job = SupervisorJob()
        private val scanScope = CoroutineScope(scope.coroutineContext + job)
        private val workDir = File(dir, "work")
        private val kick = Channel<Unit>(Channel.CONFLATED)
        private var prepJob: Job? = null
        private var worker: Job? = null
        @Volatile private var preparing = false
        @Volatile private var running = false
        @Volatile private var zip: ZipReader? = null
        private val innerZips = mutableMapOf<String, ZipReader>()

        init {
            startWorker()
        }

        fun prepare() {
            preparing = true
            refreshBusy()
            prepJob = scanScope.launch(Dispatchers.IO) {
                try {
                    val session = checkNotNull(_session.value)
                    val isArchiveName = session.fileName.endsWith(".apks", ignoreCase = true)
                    if (!isArchiveName && session.fileSize in 0..ApkSplitter.VT_MAX_BYTES) {
                        setReady(listOf(SubmissionItem(0, session.fileName, session.fileSize, ItemKind.ORIGINAL_APK)))
                    } else {
                        prepareFromZip()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Preparing $uri failed", e)
                    setPhase(Phase.Error(e.message ?: e.javaClass.simpleName))
                } finally {
                    preparing = false
                    refreshBusy()
                }
            }
        }

        fun requeue(predicate: (SubmissionItem) -> Boolean) {
            updateSession { s ->
                s.copy(apiKeyRejected = false, items = s.items.map {
                    val failed = it.status as? ItemStatus.Failed
                    if (failed != null && failed.retryable && predicate(it)) it.copy(status = ItemStatus.Queued) else it
                })
            }
            kick.trySend(Unit)
        }

        fun pause(reason: String) {
            worker?.cancel()
            prepJob?.cancel()
            updateSession { s ->
                s.copy(
                    phase = if (s.phase is Phase.Preparing) Phase.Error(reason) else s.phase,
                    items = s.items.map {
                        if (it.status is ItemStatus.Completed || it.status is ItemStatus.Failed) it
                        else it.copy(status = ItemStatus.Queued, lastError = reason)
                    },
                )
            }
            preparing = false
            startWorker()
        }

        fun close() {
            scanScope.cancel()
            kick.close()
            // Cancelled IO may still be mid-read; let it unwind before closing the zip.
            val toClose = listOfNotNull(zip) + synchronized(this) { innerZips.values.toList() }
            Thread {
                Thread.sleep(2_000)
                toClose.forEach { runCatching { it.close() } }
            }.start()
        }

        private fun setReady(items: List<SubmissionItem>) {
            updateSession { it.copy(phase = Phase.Ready, items = items) }
            kick.trySend(Unit)
        }

        /** Runs the queue each time it is kicked, until no item is left queued. */
        private fun startWorker() {
            running = false
            refreshBusy()
            worker = scanScope.launch {
                for (signal in kick) {
                    running = true
                    refreshBusy()
                    while (true) {
                        val session = _session.value ?: break
                        // A rejected key would fail every file the same way; wait for a new one instead.
                        if (session.apiKeyRejected) break
                        val next = session.items.firstOrNull { it.status == ItemStatus.Queued } ?: break
                        process(next.id)
                    }
                    running = false
                    refreshBusy()
                }
            }
        }

        private fun refreshBusy() {
            if (scan === this || scan == null) _busy.value = preparing || running
        }

        /** A big APK, or an `.apks` archive of any size: both need the zip opened. */
        private fun prepareFromZip() {
            val session = checkNotNull(_session.value)
            val opened = openZip(session.fileSize)
            zip = opened.zip
            updateSession { it.copy(fileSize = opened.size) }
            when {
                ApkSplitter.isSplitArchive(session.fileName, opened.zip) -> prepareArchive(opened.zip)
                // Size was unknown up front and the copy turned out small enough to submit as-is.
                opened.size <= ApkSplitter.VT_MAX_BYTES -> setReady(listOf(
                    SubmissionItem(0, session.fileName, opened.size, ItemKind.ORIGINAL_APK, localPath = opened.copy?.path)
                ))
                else -> {
                    val (items, entries) = splitApk(opened.zip, opened.size, ApkSplitter.baseName(session.fileName), null, 0)
                    updateSession { it.copy(entries = entries) }
                    setReady(items)
                }
            }
        }

        /**
         * `.apks`: each inner APK is submitted on its own, as `<name>.apk`, or split into
         * `<name>_slim.apk` + `<name>_bundleN.apk` when it is over the limit.
         */
        private fun prepareArchive(archive: ZipReader) {
            val inner = ApkSplitter.innerApks(archive)
            if (inner.isEmpty()) throw IOException("No APKs found in this archive")
            // Inner APKs can share a file name in different folders; keep upload names unique.
            val used = mutableSetOf<String>()
            val bases = inner.map { entry ->
                val base = ApkSplitter.baseName(entry.name)
                generateSequence(1) { it + 1 }.map { if (it == 1) base else "${base}_$it" }.first { used.add(it) }
            }
            val items = mutableListOf<SubmissionItem>()
            val entries = mutableListOf<ApkSplitter.Entry>()
            inner.zip(bases).forEach { (entry, base) ->
                if (entry.size <= ApkSplitter.VT_MAX_BYTES) {
                    items += SubmissionItem(items.size, "$base.apk", entry.size, ItemKind.ORIGINAL_APK, innerApk = entry.name)
                } else {
                    val (split, listing) = splitApk(zipFor(entry.name), entry.size, base, entry.name, items.size)
                    items += split
                    entries += listing
                }
            }
            updateSession { it.copy(entries = entries.sortedByDescending { it.compressedSize }) }
            setReady(items)
        }

        /**
         * Moves the largest files of one APK into bundles until the rest fits: returns the slim APK
         * + bundle items (ids from [firstId]) and the APK's listing keyed by [entryKey].
         */
        private fun splitApk(
            apk: ZipReader,
            apkSize: Long,
            baseName: String,
            innerApk: String?,
            firstId: Int,
        ): Pair<List<SubmissionItem>, List<ApkSplitter.Entry>> {
            val label = "$baseName.apk"
            setPhase(Phase.Preparing("Listing $label contents…"))
            val entries = ApkSplitter.listEntries(apk)

            val removed = ApkSplitter.chooseEntriesToRemove(entries, apkSize).toMutableList()
            val slimName = ApkSplitter.slimName(baseName)
            val slimFile = File(workDir, slimName)
            while (true) {
                val message = "Repacking $label without its ${removed.size} largest files " +
                    "(${formatSize(removed.sumOf { it.size })})…"
                val toCopy = entries.filter { it !in removed }.sumOf { it.size }.coerceAtLeast(1)
                var lastReported = 0L
                setPhase(Phase.Preparing(message, 0f))
                ApkSplitter.writeSlimApk(apk, removed.mapTo(HashSet()) { it.name }, slimFile) { copied ->
                    scanScope.ensureActive()
                    if (copied - lastReported >= PREP_PROGRESS_STEP) {
                        lastReported = copied
                        setPhase(Phase.Preparing(message, copied.toFloat() / toCopy))
                    }
                }
                if (slimFile.length() <= ApkSplitter.VT_MAX_BYTES) break
                // The estimate was off (e.g. re-deflating grew some entries): pull out the next one.
                val next = entries.firstOrNull { it !in removed }
                    ?: throw IOException("Could not shrink $label below 650 MB")
                removed += next
            }

            val items = mutableListOf(
                SubmissionItem(firstId, slimName, slimFile.length(), ItemKind.SLIM_APK, innerApk = innerApk, localPath = slimFile.path)
            )
            // Every bundle carries the manifest, so VirusTotal treats it as an APK.
            val manifestSize = apk.getEntry(ApkSplitter.MANIFEST)?.compressedSize ?: 0L
            val plan = ApkSplitter.planBundles(removed, manifestSize)
            val bundles = plan.bundles.map { files -> files to null } +
                plan.tooBig.map { listOf(it) to "This file alone is over VirusTotal's 650 MB limit" }
            bundles.forEachIndexed { index, (files, tooBig) ->
                items += SubmissionItem(
                    id = firstId + index + 1,
                    displayName = ApkSplitter.bundleName(baseName, index + 1),
                    // Estimate until the bundle is built; replaced by the real size then.
                    sizeBytes = manifestSize + files.sumOf { it.compressedSize },
                    kind = ItemKind.BUNDLE,
                    innerApk = innerApk,
                    bundleNumber = index + 1,
                    bundlePaths = files.map { it.name },
                    status = tooBig?.let { ItemStatus.Failed(it, retryable = false) } ?: ItemStatus.Queued,
                )
            }
            return items to entries.map { it.copy(name = entryKey(innerApk, it.name)) }
        }

        private inner class OpenedZip(val zip: ZipReader, val copy: File?, val size: Long)

        /**
         * Reads the picked file in place through its descriptor when the provider hands out a real,
         * seekable file (local storage). Otherwise (e.g. a cloud provider's pipe) copies it first.
         */
        private fun openZip(knownSize: Long): OpenedZip {
            val resolver = app.contentResolver
            val pfd = resolver.openFileDescriptor(uri, "r")
                ?: throw IOException("Can't open the APK. Pick it again.")
            if (pfd.statSize > 0) {
                val channel = FileInputStream(pfd.fileDescriptor).channel
                try {
                    return OpenedZip(ZipReader(channel, pfd), null, pfd.statSize)
                } catch (e: ZipException) {
                    pfd.close()
                    throw IOException("Not a valid APK/zip file: ${e.message}")
                } catch (e: IOException) {
                    Log.w(TAG, "Can't read APK in place, copying instead", e)
                    pfd.close()
                }
            } else {
                pfd.close()
            }

            val copy = File(workDir, "input.apk")
            workDir.mkdirs()
            val message = "Copying APK to app storage…"
            setPhase(Phase.Preparing(message, if (knownSize > 0) 0f else null))
            resolver.openInputStream(uri)!!.use { input ->
                FileOutputStream(copy).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    var lastReported = 0L
                    while (true) {
                        scanScope.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        copied += n
                        if (knownSize > 0 && copied - lastReported >= PREP_PROGRESS_STEP) {
                            lastReported = copied
                            setPhase(Phase.Preparing(message, copied.toFloat() / knownSize))
                        }
                    }
                }
            }
            val file = RandomAccessFile(copy, "r")
            return try {
                OpenedZip(ZipReader(file.channel, file), copy, copy.length())
            } catch (e: ZipException) {
                file.close()
                throw IOException("Not a valid APK/zip file: ${e.message}")
            }
        }

        /** The picked file's zip, reopened lazily after a restart. */
        @Synchronized
        private fun outerZip(): ZipReader {
            zip?.let { return it }
            val copy = File(workDir, "input.apk")
            val opened = if (copy.exists()) {
                val file = RandomAccessFile(copy, "r")
                ZipReader(file.channel, file)
            } else {
                openZip(-1).zip
            }
            zip = opened
            return opened
        }

        /**
         * The zip that [innerApk] names inside an `.apks` archive (or the picked APK itself when null).
         * Inner APKs are normally stored uncompressed and read in place; compressed ones are
         * extracted to app storage once.
         */
        @Synchronized
        private fun zipFor(innerApk: String?): ZipReader {
            val outer = outerZip()
            if (innerApk == null) return outer
            innerZips[innerApk]?.let { return it }
            val entry = outer.getEntry(innerApk) ?: throw IOException("$innerApk not found in the archive")
            val opened = outer.nested(entry) ?: run {
                val file = File(workDir, "inner_" + innerApk.replace('/', '_'))
                if (file.length() != entry.size) {
                    // Only during preparation; after a restart this happens silently mid-queue.
                    if (_session.value?.phase is Phase.Preparing) {
                        setPhase(Phase.Preparing("Extracting ${entry.name.substringAfterLast('/')}…"))
                    }
                    workDir.mkdirs()
                    outer.getInputStream(entry).use { input -> file.outputStream().use { input.copyTo(it, 256 * 1024) } }
                }
                val raf = RandomAccessFile(file, "r")
                ZipReader(raf.channel, raf)
            }
            innerZips[innerApk] = opened
            return opened
        }

        /** What gets uploaded for [item], plus a cleanup for any temporary file built for it. */
        private suspend fun sourceFor(id: Int, item: SubmissionItem): Pair<UploadSource, () -> Unit> {
            item.localPath?.let { path ->
                val file = File(path)
                if (!file.exists()) throw IOException("${file.name} is gone from app storage. Start the scan again.")
                return UploadSource(item.displayName, file.length()) { file.inputStream() } to {}
            }
            return when (item.kind) {
                ItemKind.BUNDLE -> {
                    // Built right before use and deleted after, so only one bundle is on disk at a time.
                    val bundle = File(workDir, "bundle_$id.apk")
                    withContext(Dispatchers.IO) {
                        val zipFile = zipFor(item.innerApk)
                        val paths = item.bundlePaths.toSet()
                        paths.firstOrNull { zipFile.getEntry(it) == null }?.let { throw IOException("$it not found in the APK") }
                        val total = zipFile.entries
                            .filter { it.name in paths || it.name == ApkSplitter.MANIFEST }
                            .sumOf { it.size }.coerceAtLeast(1)
                        var lastPercent = -1
                        ApkSplitter.writeBundleApk(zipFile, paths, bundle) { copied ->
                            scanScope.ensureActive()
                            val percent = (copied * 100 / total).toInt().coerceAtMost(100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                setStatus(id, ItemStatus.Packing(percent / 100f))
                            }
                        }
                    }
                    if (bundle.length() > ApkSplitter.VT_MAX_BYTES) {
                        val size = formatSize(bundle.length())
                        bundle.delete()
                        throw IOException("Bundle came out at $size, over VirusTotal's 650 MB limit")
                    }
                    updateItem(id) { it.copy(sizeBytes = bundle.length()) }
                    UploadSource(item.displayName, bundle.length()) { bundle.inputStream() } to { bundle.delete() }
                }
                else -> {
                    val innerApk = item.innerApk
                    if (innerApk != null) {
                        // An inner APK small enough to go as-is: streamed straight out of the archive.
                        val archive = withContext(Dispatchers.IO) { outerZip() }
                        val entry = archive.getEntry(innerApk) ?: throw IOException("$innerApk not found in the archive")
                        UploadSource(item.displayName, entry.size) { archive.getInputStream(entry) } to {}
                    } else {
                        UploadSource(item.displayName, item.sizeBytes) {
                            app.contentResolver.openInputStream(uri) ?: throw IOException("Can't open the APK. Pick it again.")
                        } to {}
                    }
                }
            }
        }

        /** Hash → lookup → (upload → wait for analysis). Any failure only fails this item. */
        private suspend fun process(id: Int) {
            try {
                val item = itemById(id) ?: return
                if (item.analysisId == null) {
                    val (source, cleanup) = sourceFor(id, item)
                    try {
                        val sha256 = item.sha256 ?: VirusTotalClient.sha256(source) { p ->
                            setStatus(id, ItemStatus.Hashing(p))
                        }.also { sha -> updateItem(id) { it.copy(sha256 = sha) } }

                        setStatus(id, ItemStatus.CheckingHash)
                        val known = retrying(id) {
                            client.lookupFile(sha256) { until -> setStatus(id, ItemStatus.Waiting("Waiting for API quota", until)) }
                        }
                        if (known != null) {
                            updateItem(id) {
                                it.copy(
                                    status = ItemStatus.Completed(known.stats, ResolvedVia.HASH, known.lastAnalysisMillis),
                                    reportAvailable = true,
                                    lastError = null,
                                )
                            }
                            return
                        }

                        val analysisId = retrying(id) { upload(id, source) }
                        updateItem(id) { it.copy(analysisId = analysisId, lastError = null) }
                    } finally {
                        cleanup()
                    }
                }
                awaitResult(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Item $id failed", e)
                if (e is VirusTotalException && e.isBadApiKey) {
                    // Keep the file queued and pause the whole queue until the key is fixed.
                    updateSession { s ->
                        s.copy(
                            apiKeyRejected = true,
                            items = s.items.map {
                                if (it.id == id) it.copy(status = ItemStatus.Queued, lastError = "API key rejected: ${e.message}") else it
                            },
                        )
                    }
                    return
                }
                val message = e.message ?: e.javaClass.simpleName
                updateItem(id) { it.copy(status = ItemStatus.Failed(message), lastError = message) }
            }
        }

        private suspend fun upload(id: Int, source: UploadSource): String {
            updateItem(id) { it.copy(uploadAttempts = it.uploadAttempts + 1) }
            var lastPercent = -1
            return client.upload(
                source,
                onRateLimitWait = { until -> setStatus(id, ItemStatus.Waiting("Waiting for API quota", until)) },
                onProgress = { sent, total ->
                    val percent = (sent * 100 / total.coerceAtLeast(1)).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        setStatus(id, ItemStatus.Uploading(percent / 100f))
                    }
                },
            )
        }

        /**
         * Runs [block], retrying on its own: quota errors 3× a minute apart, network/5xx errors with
         * growing back-off. Other errors (bad key, rejected file…) are rethrown straight away.
         */
        private suspend fun <T> retrying(id: Int, block: suspend () -> T): T {
            var quotaRetries = 0
            var transientRetries = 0
            while (true) {
                try {
                    return block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val quota = e is VirusTotalException && e.isQuotaExceeded
                    val transient = if (e is VirusTotalException) e.isServerError else e is IOException
                    val wait = when {
                        quota && quotaRetries++ < MAX_QUOTA_RETRIES -> 60_000L
                        transient && transientRetries < TRANSIENT_BACKOFF_MS.size -> TRANSIENT_BACKOFF_MS[transientRetries++]
                        else -> throw e
                    }
                    val reason = if (quota) "Quota exceeded, retrying" else "${e.message}. Retrying"
                    updateItem(id) {
                        it.copy(lastError = e.message, status = ItemStatus.Waiting(reason, System.currentTimeMillis() + wait))
                    }
                    delay(wait)
                }
            }
        }

        /** Polls the analysis until it completes; every check goes through the shared rate limiter. */
        private suspend fun awaitResult(id: Int) {
            val analysisId = itemById(id)?.analysisId ?: return
            setStatus(id, ItemStatus.Analyzing("queued"))
            var consecutiveErrors = 0
            repeat(MAX_POLLS) { attempt ->
                delay(if (attempt == 0) FIRST_POLL_DELAY_MS else POLL_INTERVAL_MS)
                try {
                    val result = client.getAnalysis(analysisId)
                    consecutiveErrors = 0
                    if (result.status == "completed") {
                        updateItem(id) {
                            it.copy(status = ItemStatus.Completed(result.stats, ResolvedVia.UPLOAD), reportAvailable = true)
                        }
                        return
                    }
                    val vtStatus = result.status.ifEmpty { "queued" }
                    updateItem(id) {
                        it.copy(
                            status = ItemStatus.Analyzing(vtStatus),
                            reportAvailable = it.reportAvailable || vtStatus != "queued",
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Quota hits, network drops and a not-yet-visible analysis just mean "ask again later".
                    val transient = e !is VirusTotalException || e.isQuotaExceeded || e.isNotFound || e.isServerError
                    updateItem(id) { it.copy(lastError = "Checking result: ${e.message}") }
                    if (!transient || ++consecutiveErrors >= MAX_POLL_ERRORS) {
                        throw IOException("Checking result failed: ${e.message}", e)
                    }
                }
            }
            throw IOException("Still analyzing on VirusTotal. Retry to check again.")
        }

        private fun itemById(id: Int) = _session.value?.items?.firstOrNull { it.id == id }

        /** Ignores updates from a scan that has already been replaced or cancelled. */
        private fun updateSession(transform: (ScanSession) -> ScanSession) {
            if (scan !== this || !job.isActive) return
            _session.update { it?.let(transform) }
            saveSignal.trySend(Unit)
        }

        private fun setPhase(phase: Phase) = updateSession { it.copy(phase = phase) }

        private fun setStatus(id: Int, status: ItemStatus) = updateItem(id) { it.copy(status = status) }

        private fun updateItem(id: Int, transform: (SubmissionItem) -> SubmissionItem) =
            updateSession { s -> s.copy(items = s.items.map { if (it.id == id) transform(it) else it }) }
    }

    private companion object {
        const val TAG = "ScanEngine"
        const val KEY_API_KEY = "virustotal_api_key"
        const val MAX_QUOTA_RETRIES = 3
        /** Automatic retries for network/server errors before the item is marked failed. */
        val TRANSIENT_BACKOFF_MS = longArrayOf(15_000, 60_000, 180_000)
        const val FIRST_POLL_DELAY_MS = 15_000L
        const val POLL_INTERVAL_MS = 30_000L
        /** ~25 minutes of checks per file before handing control back to the user. */
        const val MAX_POLLS = 50
        const val MAX_POLL_ERRORS = 5
        const val PREP_PROGRESS_STEP = 4L * 1024 * 1024
    }
}
