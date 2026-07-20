package messenger.client

import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import messenger.common.client.IncomingFileSignal
import messenger.common.client.MessengerClient
import messenger.common.e2ee.FileSignal
import messenger.common.util.hexToByteArray
import messenger.common.util.toHex
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("messenger.client.FileTransferHarness")

/** Strips path separators from a peer-supplied offer file name before it's used in a [File] path. */
private fun sanitizeFileName(name: String): String = name.replace(Regex("[/\\\\\\t\\n\\r]"), "_").take(120)

/**
 * Debug-only file-transfer driver for this desktop test harness — the browser test UI
 * ([messenger.client.ui.ClientUiServer]) never grew file support, so this exists purely to
 * verify the [FileSignal] wire protocol end-to-end against a real relay/Android peer without
 * needing a second phone. Opt-in via env vars, never runs otherwise.
 *
 * - `SEND_FILE_TO=<64-hex-key> SEND_FILE_PATH=<path>` sends that file once, logs OFFER →
 *   ACCEPT → each chunk ACKed → COMPLETE.
 * - `RECEIVE_FILES_DIR=<dir>` auto-accepts any incoming OFFER and writes the result there,
 *   logging progress and the final path.
 */
object FileTransferHarness {
    private const val SEND_WINDOW = 8

    fun installFromEnv(client: MessengerClient, scope: CoroutineScope) {
        System.getenv("RECEIVE_FILES_DIR")?.let { dir ->
            scope.launch { receiveLoop(client, File(dir).apply { mkdirs() }) }
            logger.info("file harness: auto-accepting incoming files into $dir")
        }
        val sendTo = System.getenv("SEND_FILE_TO")
        val sendPath = System.getenv("SEND_FILE_PATH")
        if (sendTo != null && sendPath != null) {
            scope.launch { sendFile(client, sendTo, File(sendPath), scope) }
        }
    }

    private suspend fun sendFile(client: MessengerClient, peerKeyHex: String, source: File, scope: CoroutineScope) {
        require(source.isFile) { "SEND_FILE_PATH does not point at a file: $source" }
        val peerKey = peerKeyHex.hexToByteArray()
        val fileId = UUID.randomUUID()
        val size = source.length()
        val totalChunks = FileSignal.totalChunksFor(size)

        logger.info("sending OFFER for ${source.name} ($size bytes, $totalChunks chunks) to $peerKeyHex")
        client.sendFileSignal(
            peerKey, fileId, FileSignal.OFFER,
            FileSignal.encodeOffer(FileSignal.Offer(source.name, "application/octet-stream", size, totalChunks)),
        )

        val acks = Channel<Int>(Channel.UNLIMITED)
        // Routes ACKs into `acks` as they arrive, until COMPLETE/CANCEL/UNAVAILABLE; runs in the
        // caller's scope so it's cancelled together with `sendFile` (see `finally` below).
        val dispatcher = scope.launch {
            client.fileSignals.filterIsInstance<IncomingFileSignal.Signal>().collect { signal ->
                if (signal.fileId != fileId) return@collect
                when (signal.kind) {
                    FileSignal.ACK -> acks.trySend(FileSignal.decodeIndex(signal.payload))
                    FileSignal.CANCEL -> acks.close(IllegalStateException("peer cancelled"))
                }
            }
        }
        try {
            withTimeout(30_000) {
                client.fileSignals
                    .filterIsInstance<IncomingFileSignal.Signal>()
                    .filter { it.fileId == fileId && it.kind == FileSignal.ACCEPT }
                    .first()
            }
            logger.info("peer ACCEPTed, streaming chunks")

            RandomAccessFile(source, "r").use { raf ->
                var sent = 0
                var acked = 0
                while (acked < totalChunks) {
                    while (sent < totalChunks && sent - acked < SEND_WINDOW) {
                        val offset = sent.toLong() * FileSignal.CHUNK_SIZE
                        val length = minOf(FileSignal.CHUNK_SIZE.toLong(), size - offset).toInt()
                        val buf = ByteArray(length)
                        raf.seek(offset)
                        raf.readFully(buf)
                        client.sendFileSignal(peerKey, fileId, FileSignal.CHUNK, FileSignal.encodeChunk(sent, buf))
                        sent++
                    }
                    acks.receive()
                    acked++
                    if (acked % 20 == 0 || acked == totalChunks) {
                        logger.info("progress: $acked/$totalChunks chunks acked")
                    }
                }
            }
            logger.info("transfer complete: all $totalChunks chunks acknowledged")
        } catch (e: Exception) {
            logger.warn("file send failed/timed out", e)
        } finally {
            dispatcher.cancel()
        }
    }

    private suspend fun receiveLoop(client: MessengerClient, targetDir: File) {
        val transfers = HashMap<UUID, ReceiveState>()
        client.fileSignals.filterIsInstance<IncomingFileSignal.Signal>().collect { signal ->
            when (signal.kind) {
                FileSignal.OFFER -> {
                    val offer = FileSignal.decodeOffer(signal.payload)
                    val partFile = File(targetDir, "${signal.fileId}.part")
                    val raf = RandomAccessFile(partFile, "rw").apply { setLength(offer.totalSize) }
                    transfers[signal.fileId] = ReceiveState(offer, partFile, raf, BooleanArray(offer.totalChunks))
                    logger.info("received OFFER: ${offer.fileName} (${offer.totalSize} bytes) from ${signal.peerDhIdentityKey.toHex()}")
                    client.sendFileSignal(signal.peerDhIdentityKey, signal.fileId, FileSignal.ACCEPT)
                }
                FileSignal.CHUNK -> {
                    val state = transfers[signal.fileId] ?: return@collect
                    val (index, data) = FileSignal.decodeChunk(signal.payload)
                    if (index in state.received.indices && !state.received[index]) {
                        state.raf.seek(index.toLong() * FileSignal.CHUNK_SIZE)
                        state.raf.write(data)
                        state.received[index] = true
                        state.receivedCount++
                    }
                    client.sendFileSignal(signal.peerDhIdentityKey, signal.fileId, FileSignal.ACK, FileSignal.encodeIndex(index))
                    if (state.receivedCount == state.offer.totalChunks) {
                        state.raf.close()
                        val finalFile = File(targetDir, sanitizeFileName(state.offer.fileName))
                        state.partFile.renameTo(finalFile)
                        client.sendFileSignal(signal.peerDhIdentityKey, signal.fileId, FileSignal.COMPLETE)
                        logger.info("transfer complete: saved to ${finalFile.path}")
                        transfers.remove(signal.fileId)
                    }
                }
            }
        }
    }

    private class ReceiveState(val offer: FileSignal.Offer, val partFile: File, val raf: RandomAccessFile, val received: BooleanArray) {
        var receivedCount = 0
    }
}
