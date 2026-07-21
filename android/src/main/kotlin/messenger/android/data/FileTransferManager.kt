package messenger.android.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import messenger.common.client.IncomingFileSignal
import messenger.common.e2ee.FileSignal
import messenger.common.util.hexToByteArray
import messenger.common.util.toHex

private const val TAG = "VoronFile"

/** How many chunks the sender keeps in flight before waiting for an ACK — bounded so a burst can't overrun the recipient's relay send buffer (capacity 64). */
private const val SEND_WINDOW = 8
private const val ACCEPT_TIMEOUT_MILLIS = 30_000L

// SECURITY (audit finding, 2026-07-21): handleOffer auto-ACCEPTs every incoming OFFER with no user
// confirmation and no cap on how many can be in flight at once -- any device that knows a victim's
// device key (learnable via FETCH_PREKEYS by any authenticated connection, no contact relationship
// required) could send unlimited OFFERs across distinct fileIds, each opening a real file handle +
// disk allocation (up to ~3.2GB, MAX_TOTAL_CHUNKS) and simply never sending chunks, exhausting disk
// and file descriptors. MAX_CONCURRENT_INCOMING_TRANSFERS bounds how many can exist at once; the
// stall sweep reclaims ones that stopped receiving chunks instead of holding them forever.
private const val MAX_CONCURRENT_INCOMING_TRANSFERS = 20
private const val STALL_TIMEOUT_MILLIS = 2 * 60_000L
private const val STALL_SWEEP_INTERVAL_MILLIS = 30_000L

/**
 * 1:1 file transfer with the "файлы хранятся только у сторон, минуя сервер" guarantee: bytes are
 * E2EE-chunked and streamed over the same relay pipe as messages, but the relay routes each chunk
 * live-only and never persists it (see the server's `routeFileTransfer`). A file therefore only
 * ever lands on the two endpoints' local storage. Process-scoped like [CallManager]/[ConnectionManager].
 *
 * Flow: sender OFFERs → receiver auto-ACCEPTs and opens a `.part` file → sender streams CHUNKs with
 * a bounded [SEND_WINDOW], receiver ACKs each (driving the sender's window + both progress bars) →
 * receiver COMPLETEs once every chunk landed. If the recipient is offline the relay returns
 * FILE_UNAVAILABLE and the sender's transfer fails immediately instead of parking bytes anywhere.
 */
class FileTransferManager(private val appContext: Context, private val appState: AppState) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val outgoing = ConcurrentHashMap<UUID, OutgoingTransfer>()
    private val incoming = ConcurrentHashMap<UUID, IncomingTransfer>()

    private class OutgoingTransfer(
        val fileId: UUID,
        val peerKeyHex: String,
        val source: File,
        val totalChunks: Int,
        val acks: Channel<Int> = Channel(Channel.UNLIMITED),
        val accepted: CompletableDeferred<Boolean> = CompletableDeferred(),
        @Volatile var streamJob: Job? = null,
    )

    private class IncomingTransfer(
        val fileId: UUID,
        val peerKeyHex: String,
        val partFile: File,
        val finalFile: File,
        val raf: RandomAccessFile,
        val totalSize: Long,
        val totalChunks: Int,
        val received: BooleanArray,
    ) {
        var receivedCount = 0
        @Volatile var lastActivityMillis = System.currentTimeMillis()
    }

    init {
        scope.launch {
            while (true) {
                delay(STALL_SWEEP_INTERVAL_MILLIS)
                val now = System.currentTimeMillis()
                val stale = incoming.values.filter { now - it.lastActivityMillis > STALL_TIMEOUT_MILLIS }
                for (transfer in stale) {
                    VoronLog.w(TAG, "reaping stalled incoming transfer ${transfer.fileId} (no chunk in ${STALL_TIMEOUT_MILLIS / 1000}s)")
                    failIncoming(transfer.fileId)
                }
            }
        }
    }

    private fun mediaDir(): File = File(appContext.filesDir, "media").apply { mkdirs() }

    private fun sanitize(name: String): String = name.replace(Regex("[/\\\\\\t\\n\\r]"), "_").take(120)

    // ---------------------------------------------------------------- outgoing

    /** Picks up a content [uri], copies it into local storage, and starts sending it to [peerKeyHex]. Called from the UI thread. */
    fun sendFile(peerKeyHex: String, uri: Uri) {
        scope.launch {
            val meta = resolveMeta(uri) ?: run {
                VoronLog.w(TAG, "could not resolve picked file $uri")
                return@launch
            }
            val fileId = UUID.randomUUID()
            val localCopy = File(mediaDir(), "${fileId}_${sanitize(meta.name)}")
            try {
                appContext.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "no input stream for $uri" }
                    localCopy.outputStream().use { input.copyTo(it) }
                }
            } catch (e: Exception) {
                VoronLog.w(TAG, "failed to copy picked file into local storage", e)
                localCopy.delete()
                return@launch
            }
            val size = localCopy.length()
            val totalChunks = FileSignal.totalChunksFor(size)

            withContext(Dispatchers.Main) {
                appState.appendMessage(
                    peerKeyHex,
                    ChatMessage(
                        fromMe = true,
                        text = "",
                        timestampMillis = System.currentTimeMillis(),
                        messageId = fileId.toHex16(),
                        attachmentPath = localCopy.path,
                        attachmentName = meta.name,
                        attachmentMime = meta.mime,
                        attachmentSize = size,
                        transferStatus = FileTransferStatus.OFFERED,
                    ),
                )
            }

            // Checked *after* the bubble is on screen: with no relay connection the send can't
            // go anywhere, but silently doing nothing on the tap (the old `?: return` before any
            // UI work) looked like the picker was broken — now the user sees the bubble flip to
            // "Failed, tap to retry" instead.
            val client = appState.client
            if (client == null) {
                VoronLog.w(TAG, "no relay connection, failing file send immediately")
                withContext(Dispatchers.Main) {
                    appState.updateTransfer(peerKeyHex, fileId.toHex16()) { it.copy(transferStatus = FileTransferStatus.FAILED) }
                }
                return@launch
            }

            val transfer = OutgoingTransfer(fileId, peerKeyHex, localCopy, totalChunks)
            outgoing[fileId] = transfer

            try {
                client.sendFileSignal(
                    peerKeyHex.hexToByteArray(),
                    fileId,
                    FileSignal.OFFER,
                    FileSignal.encodeOffer(FileSignal.Offer(meta.name, meta.mime, size, totalChunks)),
                )
            } catch (e: Exception) {
                VoronLog.w(TAG, "failed to send file OFFER", e)
                failOutgoing(transfer)
                return@launch
            }

            // If the peer never ACCEPTs (offline / declined / dropped), give up rather than hanging.
            scope.launch {
                val accepted = withTimeoutOrNull(ACCEPT_TIMEOUT_MILLIS) { transfer.accepted.await() }
                if (accepted != true && outgoing[fileId] === transfer) {
                    VoronLog.w(TAG, "file OFFER not accepted within timeout")
                    failOutgoing(transfer)
                }
            }
        }
    }

    private fun startStreaming(transfer: OutgoingTransfer) {
        if (transfer.streamJob != null) return
        transfer.streamJob = scope.launch {
            val client = appState.client ?: run { failOutgoing(transfer); return@launch }
            val raf = try {
                RandomAccessFile(transfer.source, "r")
            } catch (e: Exception) {
                failOutgoing(transfer); return@launch
            }
            raf.use {
                var sent = 0
                var acked = 0
                try {
                    while (acked < transfer.totalChunks) {
                        while (sent < transfer.totalChunks && sent - acked < SEND_WINDOW) {
                            val offset = sent.toLong() * FileSignal.CHUNK_SIZE
                            val length = minOf(FileSignal.CHUNK_SIZE.toLong(), transfer.source.length() - offset).toInt()
                            val buf = ByteArray(length)
                            it.seek(offset)
                            it.readFully(buf)
                            client.sendFileSignal(
                                transfer.peerKeyHex.hexToByteArray(),
                                transfer.fileId,
                                FileSignal.CHUNK,
                                FileSignal.encodeChunk(sent, buf),
                            )
                            sent++
                        }
                        // Blocks until an ACK (or a COMPLETE/failure closes the channel).
                        transfer.acks.receive()
                        acked++
                        val progress = acked.toFloat() / transfer.totalChunks
                        withContext(Dispatchers.Main) {
                            appState.updateTransferProgress(transfer.peerKeyHex, transfer.fileId.toHex16(), progress)
                        }
                    }
                } catch (e: Exception) {
                    // Channel closed by an abort (FILE_UNAVAILABLE / CANCEL) — treat as failed and stop.
                    VoronLog.d(TAG, "outgoing stream ended early: ${e.message}")
                    failOutgoing(transfer)
                    return@launch
                }
            }
            finishOutgoing(transfer)
        }
    }

    private fun finishOutgoing(transfer: OutgoingTransfer) {
        if (outgoing.remove(transfer.fileId) == null) return
        transfer.acks.close()
        scope.launch {
            withContext(Dispatchers.Main) {
                appState.updateTransfer(transfer.peerKeyHex, transfer.fileId.toHex16()) {
                    it.copy(transferStatus = FileTransferStatus.COMPLETE, transferProgress = 1f)
                }
            }
        }
    }

    private fun failOutgoing(transfer: OutgoingTransfer) {
        if (outgoing.remove(transfer.fileId) == null) return
        transfer.accepted.complete(false)
        transfer.streamJob?.cancel()
        transfer.acks.close()
        scope.launch {
            withContext(Dispatchers.Main) {
                appState.updateTransfer(transfer.peerKeyHex, transfer.fileId.toHex16()) {
                    it.copy(transferStatus = FileTransferStatus.FAILED)
                }
            }
        }
    }

    // ---------------------------------------------------------------- incoming

    private fun handleOffer(signal: IncomingFileSignal.Signal) {
        val peerKeyHex = signal.peerDhIdentityKey.toHex()
        if (appState.isBlocked(peerKeyHex)) return
        if (incoming.containsKey(signal.fileId)) return
        if (incoming.size >= MAX_CONCURRENT_INCOMING_TRANSFERS) {
            VoronLog.w(TAG, "dropping file OFFER from $peerKeyHex: too many concurrent incoming transfers")
            return
        }
        val offer = try {
            FileSignal.decodeOffer(signal.payload)
        } catch (e: Exception) {
            VoronLog.w(TAG, "dropping malformed file OFFER", e); return
        }
        val partFile = File(mediaDir(), "${signal.fileId}.part")
        val finalFile = File(mediaDir(), "${signal.fileId}_${sanitize(offer.fileName)}")
        val raf = try {
            RandomAccessFile(partFile, "rw").apply { setLength(offer.totalSize) }
        } catch (e: Exception) {
            VoronLog.w(TAG, "could not open .part file for incoming transfer", e); return
        }
        val transfer = IncomingTransfer(
            signal.fileId, peerKeyHex, partFile, finalFile, raf, offer.totalSize, offer.totalChunks,
            BooleanArray(offer.totalChunks),
        )
        incoming[signal.fileId] = transfer

        scope.launch {
            withContext(Dispatchers.Main) {
                appState.appendMessage(
                    peerKeyHex,
                    ChatMessage(
                        fromMe = false,
                        text = "",
                        timestampMillis = System.currentTimeMillis(),
                        messageId = signal.fileId.toHex16(),
                        attachmentName = offer.fileName,
                        attachmentMime = offer.mimeType,
                        attachmentSize = offer.totalSize,
                        transferStatus = FileTransferStatus.TRANSFERRING,
                    ),
                )
                appState.markUnreadIfClosed(peerKeyHex)
            }
            sendSignal(peerKeyHex, signal.fileId, FileSignal.ACCEPT)
        }
    }

    private fun handleChunk(signal: IncomingFileSignal.Signal) {
        val transfer = incoming[signal.fileId] ?: return
        transfer.lastActivityMillis = System.currentTimeMillis()
        val (index, data) = try {
            FileSignal.decodeChunk(signal.payload)
        } catch (e: Exception) {
            return
        }
        val complete = synchronized(transfer) {
            if (index < 0 || index >= transfer.totalChunks || transfer.received[index]) return@synchronized false
            // SECURITY: FileSignal.decodeChunk doesn't (and can't, on its own) validate data's
            // length against what this index is actually supposed to hold -- the OFFER's
            // totalSize/totalChunks are now internally consistent (see FileSignal.decodeOffer),
            // but nothing previously checked that an individual CHUNK's payload matches the size
            // that consistency implies. A peer could send a valid index with an oversized payload
            // (up to the relay's own ~1MB frame cap) and have it written straight to disk --
            // across MAX_TOTAL_CHUNKS possible indices, that's a disk-exhaustion DoS far beyond
            // what the OFFER ever declared, and the receiver auto-ACCEPTs every OFFER with no
            // user confirmation step, so this is fully automatic from the sender's side.
            val expectedLength = if (index == transfer.totalChunks - 1) {
                (transfer.totalSize - index.toLong() * FileSignal.CHUNK_SIZE).toInt()
            } else {
                FileSignal.CHUNK_SIZE
            }
            if (data.size != expectedLength) return@synchronized false
            transfer.raf.seek(index.toLong() * FileSignal.CHUNK_SIZE)
            transfer.raf.write(data)
            transfer.received[index] = true
            transfer.receivedCount++
            transfer.receivedCount == transfer.totalChunks
        }
        // ACK every chunk (even a duplicate) so the sender's window keeps advancing.
        scope.launch { sendSignal(transfer.peerKeyHex, signal.fileId, FileSignal.ACK, FileSignal.encodeIndex(index)) }

        val progress = transfer.receivedCount.toFloat() / transfer.totalChunks
        scope.launch {
            withContext(Dispatchers.Main) {
                appState.updateTransferProgress(transfer.peerKeyHex, signal.fileId.toHex16(), progress)
            }
        }
        if (complete) finishIncoming(transfer)
    }

    private fun finishIncoming(transfer: IncomingTransfer) {
        if (incoming.remove(transfer.fileId) == null) return
        try {
            transfer.raf.close()
            transfer.partFile.renameTo(transfer.finalFile)
        } catch (e: Exception) {
            VoronLog.w(TAG, "failed to finalize incoming file", e)
        }
        scope.launch {
            withContext(Dispatchers.Main) {
                appState.updateTransfer(transfer.peerKeyHex, transfer.fileId.toHex16()) {
                    it.copy(
                        transferStatus = FileTransferStatus.COMPLETE,
                        transferProgress = 1f,
                        attachmentPath = transfer.finalFile.path,
                    )
                }
            }
            sendSignal(transfer.peerKeyHex, transfer.fileId, FileSignal.COMPLETE)
        }
    }

    private fun failIncoming(fileId: UUID) {
        val transfer = incoming.remove(fileId) ?: return
        runCatching { transfer.raf.close() }
        transfer.partFile.delete()
        scope.launch {
            withContext(Dispatchers.Main) {
                appState.updateTransfer(transfer.peerKeyHex, fileId.toHex16()) {
                    it.copy(transferStatus = FileTransferStatus.FAILED)
                }
            }
        }
    }

    // ---------------------------------------------------------------- dispatch

    /** Dispatches a file-transfer event from [messenger.common.client.MessengerClient.fileSignals]. Safe to call from any thread. */
    fun onSignal(signal: IncomingFileSignal) {
        when (signal) {
            is IncomingFileSignal.Unavailable -> {
                // Recipient offline: abort every transfer we're sending to them.
                val peerHex = signal.peerDhIdentityKey.toHex()
                outgoing.values.filter { it.peerKeyHex == peerHex }.forEach { failOutgoing(it) }
            }
            is IncomingFileSignal.Signal -> when (signal.kind) {
                FileSignal.OFFER -> handleOffer(signal)
                FileSignal.ACCEPT -> outgoing[signal.fileId]?.let {
                    it.accepted.complete(true)
                    startStreaming(it)
                }
                FileSignal.CHUNK -> handleChunk(signal)
                FileSignal.ACK -> {
                    val index = runCatching { FileSignal.decodeIndex(signal.payload) }.getOrNull()
                    if (index != null) outgoing[signal.fileId]?.acks?.trySend(index)
                }
                FileSignal.COMPLETE -> outgoing[signal.fileId]?.let { finishOutgoing(it) }
                FileSignal.CANCEL -> {
                    outgoing[signal.fileId]?.let { failOutgoing(it) }
                    failIncoming(signal.fileId)
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun sendSignal(peerKeyHex: String, fileId: UUID, kind: Byte, payload: ByteArray = ByteArray(0)) {
        val client = appState.client ?: return
        try {
            client.sendFileSignal(peerKeyHex.hexToByteArray(), fileId, kind, payload)
        } catch (e: Exception) {
            VoronLog.w(TAG, "failed to send file signal kind=$kind", e)
        }
    }

    private class FileMeta(val name: String, val mime: String)

    private fun resolveMeta(uri: Uri): FileMeta? {
        var name: String? = null
        var mime = appContext.contentResolver.getType(uri) ?: "application/octet-stream"
        appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0) name = cursor.getString(nameIdx)
            }
        }
        val resolved = name ?: uri.lastPathSegment ?: return null
        return FileMeta(resolved, mime)
    }
}

/** 16-byte UUID as 32 hex chars, matching how transfer file ids are stored in [ChatMessage.messageId]. */
private fun UUID.toHex16(): String {
    val bytes = ByteArray(16)
    var msb = mostSignificantBits
    var lsb = leastSignificantBits
    for (i in 7 downTo 0) { bytes[i] = (msb and 0xFF).toByte(); msb = msb shr 8 }
    for (i in 15 downTo 8) { bytes[i] = (lsb and 0xFF).toByte(); lsb = lsb shr 8 }
    return bytes.toHex()
}
