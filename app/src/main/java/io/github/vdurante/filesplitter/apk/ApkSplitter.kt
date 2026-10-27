package io.github.vdurante.filesplitter.apk

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * An APK is a zip archive, so removing files only needs what APK Explorer & Editor calls a "simple
 * decompile" (unzip): the dex and binary XML are never decoded. The slim APK is not re-signed:
 * signing only matters for installing, and keeping the original META-INF lets VirusTotal still
 * show the real signing certificate.
 */
object ApkSplitter {

    /**
     * VirusTotal's maximum upload size (via `/files/upload_url`): 650 MB in VirusTotal's own
     * 1024-based units, i.e. 681,574,400 bytes, the same units the app uses to display sizes.
     */
    const val VT_MAX_BYTES = 650L * 1024 * 1024

    /** Head-room for the estimate made before the slim APK is actually written. */
    private const val SAFETY_MARGIN = 8L * 1024 * 1024

    data class Entry(val name: String, val size: Long, val compressedSize: Long)

    /** Every file in the APK (the zip paths already include all nested folders), largest first. */
    fun listEntries(zip: ZipReader): List<Entry> =
        zip.entries.asSequence()
            .filterNot { it.isDirectory }
            .map { Entry(it.name, it.size, it.compressedSize) }
            .sortedByDescending { it.compressedSize }
            .toList()

    /**
     * Picks the largest entries to pull out until the remaining APK should fit under the limit.
     * [entries] must be sorted largest first, as returned by [listEntries].
     */
    fun chooseEntriesToRemove(entries: List<Entry>, apkSize: Long): List<Entry> {
        var estimate = apkSize
        val removed = mutableListOf<Entry>()
        for (entry in entries) {
            if (estimate <= VT_MAX_BYTES - SAFETY_MARGIN) break
            removed += entry
            estimate -= entry.compressedSize + headerOverhead(entry)
        }
        return removed
    }

    /** Local header + central directory record + optional data descriptor for one entry. */
    private fun headerOverhead(entry: Entry) = 30L + 46L + 16L + 2L * entry.name.toByteArray().size

    /** Re-packs [zip] into [out], skipping [exclude]. [onProgress] gets the bytes copied so far. */
    fun writeSlimApk(zip: ZipReader, exclude: Set<String>, out: File, onProgress: (Long) -> Unit) =
        writeApk(zip, out, onProgress) { it.name !in exclude }

    /**
     * Builds one bundle: a minimal APK with the original AndroidManifest.xml plus [paths] at their
     * original locations, so VirusTotal treats the upload as an Android package. The output is
     * deterministic (same entries, times and compression), so re-scanning the same APK produces the
     * same bundle hash and the hash lookup can skip the upload.
     */
    fun writeBundleApk(zip: ZipReader, paths: Set<String>, out: File, onProgress: (Long) -> Unit) =
        writeApk(zip, out, onProgress) { it.name == MANIFEST || it.name in paths }

    /**
     * Packs the removed files into as few bundles as possible (first-fit decreasing): largest first,
     * each into the first bundle it fits in. [manifestSize] is the compressed size of the manifest
     * every bundle carries. Files that can't fit even alone are returned in [Bundles.tooBig].
     */
    fun planBundles(removed: List<Entry>, manifestSize: Long): Bundles {
        val capacity = VT_MAX_BYTES - SAFETY_MARGIN - manifestSize
        val bundles = mutableListOf<MutableList<Entry>>()
        val used = mutableListOf<Long>()
        val tooBig = mutableListOf<Entry>()
        for (entry in removed.sortedByDescending { it.compressedSize }) {
            val cost = entry.compressedSize + headerOverhead(entry)
            if (cost > capacity) {
                tooBig += entry
                continue
            }
            val index = used.indexOfFirst { it + cost <= capacity }
            if (index >= 0) {
                bundles[index] += entry
                used[index] += cost
            } else {
                bundles += mutableListOf(entry)
                used += cost
            }
        }
        return Bundles(bundles, tooBig)
    }

    class Bundles(val bundles: List<List<Entry>>, val tooBig: List<Entry>)

    /** `app.apk` → `app_slim.apk`. */
    fun slimName(baseName: String) = "${baseName}_slim.apk"

    /** `app.apk`, 2 → `app_bundle2.apk`. */
    fun bundleName(baseName: String, number: Int) = "${baseName}_bundle$number.apk"

    /** `app.apk` → `app`; `splits/base-master.apk` → `base-master`. */
    fun baseName(fileName: String) = fileName.substringAfterLast('/').removeSuffix(".apk").removeSuffix(".APK")

    /**
     * A split-APK archive (`.apks` from bundletool or SAI): a zip of APKs rather than an APK itself.
     * Recognized by extension, or by holding APKs but no AndroidManifest.xml of its own.
     */
    fun isSplitArchive(fileName: String, zip: ZipReader): Boolean {
        if (fileName.endsWith(".apks", ignoreCase = true)) return true
        return zip.getEntry(MANIFEST) == null && innerApks(zip).isNotEmpty()
    }

    /**
     * The APKs inside a split archive, base first. Other members (`toc.pb`, `meta.sai_v1.json`,
     * icons) are metadata and aren't scanned.
     */
    fun innerApks(zip: ZipReader): List<ZipReader.Entry> =
        zip.entries
            .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
            .sortedWith(compareBy({ !baseName(it.name).startsWith("base") }, { it.name }))

    const val MANIFEST = "AndroidManifest.xml"

    private fun writeApk(zip: ZipReader, out: File, onProgress: (Long) -> Unit, include: (ZipReader.Entry) -> Boolean) {
        out.parentFile?.mkdirs()
        var copied = 0L
        ZipOutputStream(BufferedOutputStream(FileOutputStream(out), 256 * 1024)).use { zos ->
            val buffer = ByteArray(64 * 1024)
            for (original in zip.entries) {
                if (!include(original)) continue
                // Fresh entry: compressed sizes and extras from the original don't apply after
                // re-deflating, and ZipOutputStream rejects mismatches.
                val entry = ZipEntry(original.name).apply {
                    time = original.time
                    if (original.method == ZipReader.STORED) {
                        method = ZipEntry.STORED
                        size = original.size
                        compressedSize = original.size
                        crc = original.crc
                    } else {
                        method = ZipEntry.DEFLATED
                    }
                }
                zos.putNextEntry(entry)
                if (!original.isDirectory) {
                    zip.getInputStream(original).use { input ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            zos.write(buffer, 0, n)
                            copied += n
                            onProgress(copied)
                        }
                    }
                }
                zos.closeEntry()
            }
        }
    }
}
