package io.github.m96chan.droidrunner.npu

/**
 * Reading a zip's table of contents without holding the zip (issue #82).
 *
 * Qualcomm ships its runtime as a 64MB AAR carrying every Hexagon generation
 * from V68 to V81. A phone needs one of them, and the three version-independent
 * libraries beside it — 38MB for a V75 device, and none of the other five
 * generations. A zip is readable back-to-front over HTTP range requests, so
 * that is what this parses: the end-of-directory record, then the central
 * directory, then the local header that says where an entry's bytes begin.
 *
 * Kept free of any I/O so the format work is testable against a zip built in
 * the test itself, rather than against a server.
 */
internal object ZipIndex {

    data class Entry(
        val name: String,
        val localHeaderOffset: Long,
        val compressedBytes: Long,
        val uncompressedBytes: Long,
        /** Deflated when true, stored verbatim when false. */
        val deflated: Boolean,
    )

    /** Where the central directory sits in the file. */
    data class Directory(val offset: Long, val bytes: Long)

    /**
     * How many bytes from the end of a zip are enough to find the
     * end-of-directory record: its own 22 bytes plus the longest comment the
     * format allows to follow it.
     */
    const val TAIL_BYTES = 22 + 0xFFFF

    /** Enough of a local file header to learn where its data starts. */
    const val LOCAL_HEADER_BYTES = 30

    /**
     * Finds the central directory, given [tail] — the last [TAIL_BYTES] of the
     * file, or the whole file when it is smaller.
     *
     * The record is found by scanning backwards, because a zip comment may
     * contain the signature and the *last* match is the real one.
     */
    fun directoryIn(tail: ByteArray): Directory {
        var at = tail.size - 22
        while (at >= 0) {
            if (tail.u32(at) == END_OF_DIRECTORY) {
                val bytes = tail.u32(at + 12)
                val offset = tail.u32(at + 16)
                require(offset != ZIP64_MARKER && bytes != ZIP64_MARKER) {
                    "Zip64 archives are not supported"
                }
                return Directory(offset = offset, bytes = bytes)
            }
            at--
        }
        error("Not a zip: no end-of-directory record")
    }

    /** Every entry described by [directory], the central directory's bytes. */
    fun entriesIn(directory: ByteArray): List<Entry> {
        val entries = mutableListOf<Entry>()
        var at = 0
        while (at + 46 <= directory.size && directory.u32(at) == DIRECTORY_HEADER) {
            val nameLength = directory.u16(at + 28)
            val extraLength = directory.u16(at + 30)
            val commentLength = directory.u16(at + 32)
            entries += Entry(
                name = String(directory, at + 46, nameLength, Charsets.UTF_8),
                localHeaderOffset = directory.u32(at + 42),
                compressedBytes = directory.u32(at + 20),
                uncompressedBytes = directory.u32(at + 24),
                deflated = directory.u16(at + 10) == METHOD_DEFLATE,
            )
            at += 46 + nameLength + extraLength + commentLength
        }
        return entries
    }

    /**
     * Where [entry]'s compressed bytes begin, given the first
     * [LOCAL_HEADER_BYTES] of its local header.
     *
     * The local header cannot be skipped by assuming it matches the central
     * directory's copy: the two carry independent extra-field lengths, and
     * padding an entry to an alignment boundary is done in the local one.
     */
    fun dataStart(entry: Entry, localHeader: ByteArray): Long {
        require(localHeader.u32(0) == LOCAL_HEADER) { "Not a local file header for ${entry.name}" }
        val nameLength = localHeader.u16(26)
        val extraLength = localHeader.u16(28)
        return entry.localHeaderOffset + LOCAL_HEADER_BYTES + nameLength + extraLength
    }

    private const val END_OF_DIRECTORY = 0x06054b50L
    private const val DIRECTORY_HEADER = 0x02014b50L
    private const val LOCAL_HEADER = 0x04034b50L
    private const val METHOD_DEFLATE = 8
    private const val ZIP64_MARKER = 0xFFFFFFFFL

    private fun ByteArray.u16(at: Int): Int =
        (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.u32(at: Int): Long =
        u16(at).toLong() or (u16(at + 2).toLong() shl 16)
}
