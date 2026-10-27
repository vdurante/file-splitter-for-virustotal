package io.github.vdurante.filesplitter

import java.util.Locale

/**
 * Formats a size the way VirusTotal shows it: 1024-based units labeled KB/MB/GB, two decimals
 * (e.g. 535,000,000 bytes → "510.21 MB"). Android's own Formatter uses 1000-based units, which
 * made the same file look bigger in the app than on VirusTotal.
 */
fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.US, "%.2f %s", value, units[unit])
}
