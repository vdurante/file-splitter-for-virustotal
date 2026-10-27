package io.github.vdurante.filesplitter.apk

import java.io.Closeable
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Calendar
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipException

/**
 * Minimal random-access zip reader over a [FileChannel].
 *
 * java.util.zip.ZipFile only accepts a path, and Android denies re-opening a picked document via
 * /proc/self/fd, so without this the whole APK would have to be copied first. Supports Zip64, which
 * multi-GB APKs can need. Positional channel reads keep concurrent entry streams independent.
 */
class ZipReader private constructor(
    private val channel: FileChannel,
    /** Closed together with the reader; null for a [nested] reader, which doesn't own the channel. */
    private val owner: Closeable?,
    /** Where this zip starts inside [channel]: 0, or the data offset of a [nested] zip entry. */
    private val start: Long,
    private val length: Long,
) : Closeable {

    constructor(channel: FileChannel, owner: Closeable) : this(channel, owner, 0, channel.size())

    class Entry(
        val name: String,
        val method: Int,
        val crc: Long,
        val compressedSize: Long,
        val size: Long,
        val time: Long,
        internal val localHeaderOffset: Long,
    ) {
        val isDirectory get() = name.endsWith('/')
    }

    /** Entries in archive (central directory) order. */
    val entries: List<Entry> = readCentralDirectory()

    private val byName = entries.associateBy { it.name }

    fun getEntry(name: String): Entry? = byName[name]

    /** Uncompressed contents of [entry]. Only STORED (0) and DEFLATED (8) are supported. */
    fun getInputStream(entry: Entry): InputStream {
        val dataStart = dataOffset(entry)
        val raw = ChannelInputStream(dataStart, dataStart + entry.compressedSize)
        return when (entry.method) {
            STORED -> raw
            DEFLATED -> {
                val inflater = Inflater(true)
                object : InflaterInputStream(raw.withTrailingPadByte(), inflater, 64 * 1024) {
                    override fun close() {
                        super.close()
                        inflater.end()
                    }
                }
            }
            else -> throw ZipException("Unsupported compression method ${entry.method} for ${entry.name}")
        }
    }

    /**
     * Opens a zip stored *inside* this one (e.g. an APK in an .apks archive) in place, without
     * copying it. Only possible for STORED entries; returns null for compressed ones.
     */
    fun nested(entry: Entry): ZipReader? {
        if (entry.method != STORED) return null
        return ZipReader(channel, null, start + dataOffset(entry), entry.size)
    }

    override fun close() {
        // A nested reader shares its parent's channel; only the outermost reader closes it.
        if (owner == null) return
        channel.close()
        owner.close()
    }

    /** Offset of [entry]'s data, relative to the start of this zip. */
    private fun dataOffset(entry: Entry): Long {
        val header = read(entry.localHeaderOffset, LOCAL_HEADER_SIZE)
        if (header.getInt(0) != LOCAL_HEADER_SIG) throw ZipException("Bad local header for ${entry.name}")
        return entry.localHeaderOffset + LOCAL_HEADER_SIZE + header.u16(26) + header.u16(28)
    }

    private fun readCentralDirectory(): List<Entry> {
        val fileSize = length
        val tailSize = minOf(fileSize, (EOCD_SIZE + 0xFFFF).toLong()).toInt()
        if (tailSize < EOCD_SIZE) throw ZipException("File too small to be a zip")
        val tail = read(fileSize - tailSize, tailSize)
        val eocd = (tailSize - EOCD_SIZE downTo 0).firstOrNull { tail.getInt(it) == EOCD_SIG }
            ?: throw ZipException("End of central directory not found")

        var count = tail.u16(eocd + 10)
        var cdSize = tail.u32(eocd + 12)
        var cdOffset = tail.u32(eocd + 16)
        if (count == 0xFFFFL || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
            val locator = eocd - 20
            if (locator < 0 || tail.getInt(locator) != ZIP64_LOCATOR_SIG) {
                throw ZipException("Zip64 locator missing")
            }
            val zip64Eocd = read(tail.getLong(locator + 8), 56)
            if (zip64Eocd.getInt(0) != ZIP64_EOCD_SIG) throw ZipException("Bad Zip64 end record")
            count = zip64Eocd.getLong(32)
            cdSize = zip64Eocd.getLong(40)
            cdOffset = zip64Eocd.getLong(48)
        }
        if (cdSize > Int.MAX_VALUE || cdOffset + cdSize > fileSize) {
            throw ZipException("Central directory out of range")
        }

        val cd = read(cdOffset, cdSize.toInt())
        val result = ArrayList<Entry>(count.coerceAtMost(100_000L).toInt())
        var p = 0
        while (p + CD_HEADER_SIZE <= cd.limit() && cd.getInt(p) == CD_HEADER_SIG) {
            val nameLen = cd.u16(p + 28).toInt()
            val extraLen = cd.u16(p + 30).toInt()
            val commentLen = cd.u16(p + 32).toInt()
            val nameBytes = ByteArray(nameLen) { cd.get(p + CD_HEADER_SIZE + it) }
            var compressed = cd.u32(p + 20)
            var size = cd.u32(p + 24)
            var offset = cd.u32(p + 42)

            // Zip64 extra field: 64-bit values appear only for the fields that overflowed, in this order.
            var e = p + CD_HEADER_SIZE + nameLen
            val extraEnd = e + extraLen
            while (e + 4 <= extraEnd) {
                val id = cd.u16(e)
                val len = cd.u16(e + 2).toInt()
                if (id == 0x0001L) {
                    var v = e + 4
                    if (size == 0xFFFFFFFFL) { size = cd.getLong(v); v += 8 }
                    if (compressed == 0xFFFFFFFFL) { compressed = cd.getLong(v); v += 8 }
                    if (offset == 0xFFFFFFFFL) { offset = cd.getLong(v) }
                }
                e += 4 + len
            }

            result += Entry(
                name = String(nameBytes, Charsets.UTF_8),
                method = cd.u16(p + 10).toInt(),
                crc = cd.u32(p + 16),
                compressedSize = compressed,
                size = size,
                time = dosToMillis(cd.u16(p + 14).toInt(), cd.u16(p + 12).toInt()),
                localHeaderOffset = offset,
            )
            p += CD_HEADER_SIZE + nameLen + extraLen + commentLen
        }
        return result
    }

    private fun read(position: Long, length: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        var pos = position
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, start + pos)
            if (n < 0) throw ZipException("Unexpected end of file")
            pos += n
        }
        return buffer.flip() as ByteBuffer
    }

    private inner class ChannelInputStream(private var position: Long, private val end: Long) : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (position >= end) return -1
            val n = minOf(len.toLong(), end - position).toInt()
            val read = channel.read(ByteBuffer.wrap(b, off, n), start + position)
            if (read < 0) return -1
            position += read
            return read
        }

        override fun available() = minOf(end - position, Int.MAX_VALUE.toLong()).toInt()

        /** A nowrap Inflater may need one extra byte past the data to finish (same trick as ZipFile). */
        fun withTrailingPadByte(): InputStream = object : InputStream() {
            private var padded = false
            override fun read(): Int {
                val v = this@ChannelInputStream.read()
                if (v >= 0) return v
                if (padded) return -1
                padded = true
                return 0
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = this@ChannelInputStream.read(b, off, len)
                if (n >= 0 || padded || len == 0) return n
                padded = true
                b[off] = 0
                return 1
            }
        }
    }

    companion object {
        const val STORED = 0
        const val DEFLATED = 8
        private const val LOCAL_HEADER_SIG = 0x04034b50
        private const val CD_HEADER_SIG = 0x02014b50
        private const val EOCD_SIG = 0x06054b50
        private const val ZIP64_LOCATOR_SIG = 0x07064b50
        private const val ZIP64_EOCD_SIG = 0x06064b50
        private const val LOCAL_HEADER_SIZE = 30
        private const val CD_HEADER_SIZE = 46
        private const val EOCD_SIZE = 22

        private fun ByteBuffer.u16(index: Int) = (getShort(index).toInt() and 0xFFFF).toLong()
        private fun ByteBuffer.u32(index: Int) = getInt(index).toLong() and 0xFFFFFFFFL

        private fun dosToMillis(date: Int, time: Int): Long = Calendar.getInstance().run {
            clear()
            set(
                1980 + (date shr 9 and 0x7F), (date shr 5 and 0x0F) - 1, date and 0x1F,
                time shr 11 and 0x1F, time shr 5 and 0x3F, (time and 0x1F) * 2,
            )
            timeInMillis
        }
    }
}
