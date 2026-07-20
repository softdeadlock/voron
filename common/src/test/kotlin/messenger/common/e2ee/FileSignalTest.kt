package messenger.common.e2ee

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FileSignalTest {
    @Test
    fun `chunk round-trips through encode and decode`() {
        val fileId = UUID.randomUUID()
        val data = ByteArray(FileSignal.CHUNK_SIZE) { (it % 256).toByte() }
        val frame = FileSignal.encode(fileId, FileSignal.CHUNK, FileSignal.encodeChunk(7, data))

        val decoded = FileSignal.decode(frame)
        assertEquals(fileId, decoded.fileId)
        assertEquals(FileSignal.CHUNK, decoded.kind)
        val (index, bytes) = FileSignal.decodeChunk(decoded.payload)
        assertEquals(7, index)
        assertArrayEquals(data, bytes)
    }

    @Test
    fun `offer round-trips with unicode name and large size`() {
        // totalChunks must be exactly ceil(totalSize / CHUNK_SIZE) (maxOf(1, ...) for a zero size) —
        // decodeOffer now enforces this (see its SECURITY comment), so these two have to actually
        // agree, unlike an arbitrary pair of "big-looking" numbers. Also has to stay within
        // MAX_TOTAL_CHUNKS (200_000, ~3.27GB) -- this is exactly that cap's largest valid file,
        // evenly divisible by CHUNK_SIZE so the ceiling division lands on it precisely.
        val totalSize = 200_000L * FileSignal.CHUNK_SIZE
        val totalChunks = 200_000
        val offer = FileSignal.Offer("отчёт 📄.pdf", "application/pdf", totalSize, totalChunks)
        val decoded = FileSignal.decodeOffer(FileSignal.encodeOffer(offer))
        assertEquals("отчёт 📄.pdf", decoded.fileName)
        assertEquals("application/pdf", decoded.mimeType)
        assertEquals(totalSize, decoded.totalSize)
        assertEquals(totalChunks, decoded.totalChunks)
    }

    @Test
    fun `decodeOffer rejects a totalSize inconsistent with totalChunks`() {
        // A peer claiming a tiny totalChunks (cheap, passes the existing cap) alongside a huge
        // totalSize would otherwise sail through: FileTransferManager.handleOffer takes totalSize
        // at face value for RandomAccessFile.setLength, which happily sparse-allocates a file far
        // bigger than the (small, capped) chunk count could ever actually fill.
        val mismatched = FileSignal.encodeOffer(FileSignal.Offer("big.bin", "application/octet-stream", totalSize = 5_000_000_000L, totalChunks = 5))
        assertThrows(IllegalArgumentException::class.java) { FileSignal.decodeOffer(mismatched) }
    }

    @Test
    fun `ack index round-trips`() {
        val decoded = FileSignal.decode(FileSignal.encode(UUID.randomUUID(), FileSignal.ACK, FileSignal.encodeIndex(42)))
        assertEquals(FileSignal.ACK, decoded.kind)
        assertEquals(42, FileSignal.decodeIndex(decoded.payload))
    }
}
