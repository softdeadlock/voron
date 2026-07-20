package messenger.common.e2ee

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * The E2EE plaintext for file transfer — parallel to [messenger.common.e2ee.CallSignal], carried
 * inside a [messenger.common.protocol.TransportFrame.FILE_TRANSFER] frame. The relay forwards these
 * opaquely and, unlike a [ROUTE][messenger.common.protocol.TransportFrame.ROUTE] message, never
 * mailboxes them: file bytes only ever exist on the two endpoints, transiting the relay live (in
 * RAM) and never touching its disk/Postgres. If the recipient is offline the sender is told
 * immediately (FILE_UNAVAILABLE) and the transfer is cancelled rather than parked server-side.
 *
 * Wire layout: `[16-byte fileId][1-byte kind][payload]`. Unlike [CallSignal] the payload is raw
 * bytes (chunks are binary), not UTF-8 text.
 *
 * A transfer is: OFFER (name/mime/size/chunk-count) → receiver ACCEPTs → sender streams CHUNKs,
 * receiver ACKs each so the sender can window its in-flight chunks → receiver COMPLETEs once every
 * chunk landed. Either side may CANCEL.
 */
object FileSignal {
    const val OFFER: Byte = 0x01
    const val ACCEPT: Byte = 0x02
    const val CHUNK: Byte = 0x03
    const val ACK: Byte = 0x04
    const val COMPLETE: Byte = 0x05
    const val CANCEL: Byte = 0x06

    /** Plaintext bytes per CHUNK. Kept well under the Noise transport message limit even after E2EE + framing overhead. */
    const val CHUNK_SIZE = 16 * 1024

    private const val FILE_ID_LENGTH = 16
    private const val HEADER_LENGTH = FILE_ID_LENGTH + 1

    // SECURITY: totalChunks comes straight off the wire from a peer — FileTransferManager.handleOffer
    // allocates a BooleanArray(offer.totalChunks) straight from it. Same OOM shape already fixed in
    // PreKeyCodec's otpCount: an uncapped value throws OutOfMemoryError, an Error the surrounding
    // catch(Exception) doesn't catch. Bounded generously above any real file (CHUNK_SIZE * this ~= 3.2GB).
    private const val MAX_TOTAL_CHUNKS = 200_000

    fun encode(fileId: UUID, kind: Byte, payload: ByteArray = ByteArray(0)): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_LENGTH + payload.size)
        buffer.putLong(fileId.mostSignificantBits)
        buffer.putLong(fileId.leastSignificantBits)
        buffer.put(kind)
        buffer.put(payload)
        return buffer.array()
    }

    class Decoded(val fileId: UUID, val kind: Byte, val payload: ByteArray)

    fun decode(bytes: ByteArray): Decoded {
        require(bytes.size >= HEADER_LENGTH) { "file signal too short to contain a header" }
        val buffer = ByteBuffer.wrap(bytes)
        val fileId = UUID(buffer.long, buffer.long)
        val kind = buffer.get()
        val payload = ByteArray(bytes.size - HEADER_LENGTH)
        buffer.get(payload)
        return Decoded(fileId, kind, payload)
    }

    // ---- OFFER payload: [2B nameLen][name][1B mimeLen][mime][8B totalSize][4B totalChunks] ----

    /** How many [CHUNK_SIZE] chunks a file of [totalSize] bytes splits into — the one formula both a real sender (deciding how many chunks to stream) and [decodeOffer] (validating a peer's claim) must agree on. */
    fun totalChunksFor(totalSize: Long): Int = if (totalSize == 0L) 1 else ((totalSize + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()

    class Offer(val fileName: String, val mimeType: String, val totalSize: Long, val totalChunks: Int)

    fun encodeOffer(offer: Offer): ByteArray {
        val nameBytes = offer.fileName.toByteArray(StandardCharsets.UTF_8)
        val mimeBytes = offer.mimeType.toByteArray(StandardCharsets.UTF_8)
        require(nameBytes.size <= 0xFFFF) { "file name too long" }
        require(mimeBytes.size <= 0xFF) { "mime type too long" }
        val buffer = ByteBuffer.allocate(2 + nameBytes.size + 1 + mimeBytes.size + 8 + 4)
        buffer.putShort(nameBytes.size.toShort())
        buffer.put(nameBytes)
        buffer.put(mimeBytes.size.toByte())
        buffer.put(mimeBytes)
        buffer.putLong(offer.totalSize)
        buffer.putInt(offer.totalChunks)
        return buffer.array()
    }

    fun decodeOffer(payload: ByteArray): Offer {
        val buffer = ByteBuffer.wrap(payload)
        val nameLen = buffer.short.toInt() and 0xFFFF
        val nameBytes = ByteArray(nameLen).also { buffer.get(it) }
        val mimeLen = buffer.get().toInt() and 0xFF
        val mimeBytes = ByteArray(mimeLen).also { buffer.get(it) }
        val totalSize = buffer.long
        val totalChunks = buffer.int
        // Only totalChunks feeds an allocation (FileTransferManager sizes a BooleanArray from it) —
        // totalSize is just recorded/used for RandomAccessFile.setLength (a cheap sparse-file
        // extension, not an eager allocation), so it only needs a sanity floor, not a matching cap.
        require(totalChunks in 0..MAX_TOTAL_CHUNKS) { "total chunk count out of range: $totalChunks" }
        require(totalSize >= 0) { "total size out of range: $totalSize" }
        // SECURITY: totalSize and totalChunks are two independent wire fields with nothing else
        // tying them together -- a peer could claim a tiny totalChunks (passing the cap above) with
        // an enormous totalSize (still >= 0, passing that check too). FileTransferManager.handleOffer
        // takes totalSize at face value for RandomAccessFile.setLength: on a real filesystem an
        // absurd value usually just throws (caught, offer rejected), but anything under the
        // filesystem's max file size still succeeds as a sparse allocation, leaving a stray
        // multi-hundred-GB-"sized" (mostly sparse) file with no data behind it once the (small,
        // capped) totalChunks worth of real bytes ever arrive. Requiring the two fields to agree
        // with how a real sender always computes them (see FileTransferManager.sendFile) closes
        // that gap without needing a matching cap on totalSize itself.
        require(totalChunks == totalChunksFor(totalSize)) { "totalChunks ($totalChunks) inconsistent with totalSize ($totalSize)" }
        return Offer(String(nameBytes, StandardCharsets.UTF_8), String(mimeBytes, StandardCharsets.UTF_8), totalSize, totalChunks)
    }

    // ---- CHUNK payload: [4B chunkIndex][bytes] ; ACK payload: [4B chunkIndex] ----

    fun encodeChunk(index: Int, data: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(4 + data.size)
        buffer.putInt(index)
        buffer.put(data)
        return buffer.array()
    }

    /** Returns the chunk index and a view of its bytes. */
    fun decodeChunk(payload: ByteArray): Pair<Int, ByteArray> {
        val buffer = ByteBuffer.wrap(payload)
        val index = buffer.int
        val data = ByteArray(payload.size - 4).also { buffer.get(it) }
        return index to data
    }

    fun encodeIndex(index: Int): ByteArray = ByteBuffer.allocate(4).putInt(index).array()

    fun decodeIndex(payload: ByteArray): Int = ByteBuffer.wrap(payload).int
}
