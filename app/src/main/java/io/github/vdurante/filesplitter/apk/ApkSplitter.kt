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
     * VirusTotal's maximum upload size (via `/files/upload_url`). Its docs say "650MB" without a
     * unit, so this uses the stricter decimal reading: 650,000,000 bytes (not 650 MiB). It also
     * matches what Android shows as "650 MB".
     */
    const val VT_MAX_BYTES = 650_000_000L

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

    fun slimName(apkName: String) = apkName.removeSuffix(".apk") + "__slim.apk"

    fun bundleName(apkName: String, number: Int) = apkName.removeSuffix(".apk") + "__bundle_$number.apk"

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
