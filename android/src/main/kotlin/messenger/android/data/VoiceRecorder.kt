package messenger.android.data

import android.content.Context
import android.media.MediaRecorder
import java.io.File

/**
 * Thin wrapper over [MediaRecorder] for voice messages — records straight to an AAC/MP4 file under
 * the same `media` directory [FileTransferManager] uses. The resulting file is read into memory and
 * handed to [ConnectionManager.sendVoiceMessage], which embeds the audio bytes directly in the
 * [messenger.common.client.ApplicationMessage] and sends it through the ordinary mailboxed ROUTE
 * pipe — unlike [FileTransferManager]'s live-only transfer, this is delivered even if the recipient
 * is offline when it's sent.
 */
class VoiceRecorder(private val appContext: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    /** Starts recording to a fresh file, or returns false (nothing changed) if the recorder couldn't start — e.g. mic already in use by a call. */
    fun start(): Boolean {
        if (recorder != null) return false
        val dir = File(appContext.filesDir, "media").apply { mkdirs() }
        val file = File(dir, "voice_${System.currentTimeMillis()}.m4a")
        val r = MediaRecorder(appContext)
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            // Voice notes are speech, not music — mono at a low bitrate stays perfectly
            // intelligible and cuts file size roughly in half versus the earlier 64kbps setting
            // (which also left channel count at the device default, occasionally stereo).
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(32_000)
            r.setAudioSamplingRate(22_050)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            outputFile = file
            true
        } catch (e: Exception) {
            VoronLog.w("VoronVoiceRecorder", "failed to start recording", e)
            r.release()
            file.delete()
            false
        }
    }

    /** Stops and returns the recorded file, or null if nothing was recording or the recording was too short to be valid. */
    fun stop(): File? {
        val r = recorder ?: return null
        val file = outputFile
        recorder = null
        outputFile = null
        return try {
            r.stop()
            r.release()
            file
        } catch (e: Exception) {
            // MediaRecorder.stop() throws IllegalStateException if stop is called too soon after
            // start (no audio frames captured yet) — treat exactly like a cancelled recording.
            VoronLog.w("VoronVoiceRecorder", "recording too short or failed to finalize", e)
            r.release()
            file?.delete()
            null
        }
    }

    /** Stops (if needed) and discards the in-progress recording. */
    fun cancel() {
        val r = recorder
        val file = outputFile
        recorder = null
        outputFile = null
        if (r != null) {
            runCatching { r.stop() }
            r.release()
        }
        file?.delete()
    }
}
