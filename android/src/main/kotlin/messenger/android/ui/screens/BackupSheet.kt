package messenger.android.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import messenger.android.data.BackupManager
import messenger.android.data.VoronLog
import messenger.android.ui.theme.voronNeutralIconContainerColor
import messenger.android.ui.theme.voronNeutralIconTint
import messenger.android.ui.theme.voronSheetContainerColor

private const val TAG = "VoronBackup"

private enum class ExportStep { INTRO, PHRASE, DONE }

/**
 * Generates a fresh recovery phrase + encrypted archive (see [BackupManager]) and walks the user
 * through writing the phrase down before letting them save the file — the phrase is never shown
 * again and never itself written to disk, so skipping straight to "save file" would produce a
 * backup nobody could ever restore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportBackupSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var step by remember { mutableStateOf(ExportStep.INTRO) }
    var confirmedWrittenDown by remember { mutableStateOf(false) }

    // createBackup() runs PBKDF2 (600k iterations) + reads/encrypts every store file — ~1-2s of
    // work that must NOT run on the main thread (it used to, inside remember{}, freezing the UI as
    // the sheet opened). Produced off-Main; the sheet shows a brief "preparing" state until ready.
    val backup by produceState<Pair<List<String>, ByteArray>?>(initialValue = null) {
        value = withContext(Dispatchers.Default) { BackupManager(context).createBackup() }
    }
    val phrase = backup?.first
    val archiveBytes = backup?.second

    val saveFileContract = remember { ActivityResultContracts.CreateDocument("application/octet-stream") }
    val saveFileLauncher = rememberLauncherForActivityResult(saveFileContract) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = archiveBytes ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        }.onSuccess {
            step = ExportStep.DONE
        }.onFailure {
            VoronLog.w(TAG, "failed to write backup archive", it)
            Toast.makeText(context, "Couldn't save the file", Toast.LENGTH_SHORT).show()
        }
    }
    val defaultFileName = "voron-backup-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.voronbackup"

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = voronSheetContainerColor()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BackupIcon(Icons.Filled.CloudUpload)
            Spacer(Modifier.height(16.dp))

            when (step) {
                ExportStep.INTRO -> {
                    Text("Back up your account", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Includes your identity, contacts, and message history. Protected by a 12-word " +
                            "recovery phrase you'll write down next — anyone with the file AND the phrase " +
                            "can read everything, so keep them apart.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    PrimaryActionButton(
                        text = if (phrase == null) "Preparing…" else "Show my recovery phrase",
                        enabled = phrase != null,
                        onClick = { step = ExportStep.PHRASE },
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                }

                ExportStep.PHRASE -> if (phrase == null) {
                    Text("Preparing…", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                } else {
                    Text("Write these 12 words down", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "In this exact order, on paper, somewhere safe. This is the only time Voron shows " +
                            "them — without them the backup file can't be opened by anyone, including you.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    PhraseGrid(phrase)
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Checkbox(checked = confirmedWrittenDown, onCheckedChange = { confirmedWrittenDown = it })
                        Text(
                            "I've written this down somewhere safe",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    Spacer(Modifier.height((if (confirmedWrittenDown) 4 else 20).dp))
                    PrimaryActionButton(
                        text = "Save encrypted backup file",
                        enabled = confirmedWrittenDown,
                        onClick = { runCatching { saveFileLauncher.launch(defaultFileName) } },
                    )
                }

                ExportStep.DONE -> {
                    Text("Backup saved", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Keep the file and the 12 words in two different places. Either one alone is useless.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    PrimaryActionButton(text = "Done", onClick = onDismiss)
                }
            }
        }
    }
}

private enum class RestoreStep { PICK_FILE, ENTER_PHRASE, RESTORING, DONE, ERROR }

/** Restores a device from a file produced by [ExportBackupSheet] — see [BackupManager.restoreBackup] for exactly which files this overwrites and why a manual app restart is required afterward. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreBackupSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var step by remember { mutableStateOf(RestoreStep.PICK_FILE) }
    var phraseText by remember { mutableStateOf("") }
    var pickedBytes by remember { mutableStateOf<ByteArray?>(null) }
    var errorMessage by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    val pickFileContract = remember { ActivityResultContracts.OpenDocument() }
    val pickFileLauncher = rememberLauncherForActivityResult(pickFileContract) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.onSuccess { bytes ->
            if (bytes == null) return@onSuccess
            pickedBytes = bytes
            step = RestoreStep.ENTER_PHRASE
        }.onFailure {
            VoronLog.w(TAG, "failed to read backup archive", it)
            Toast.makeText(context, "Couldn't read that file", Toast.LENGTH_SHORT).show()
        }
    }

    fun attemptRestore() {
        val bytes = pickedBytes ?: return
        val words = phraseText.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        step = RestoreStep.RESTORING
        // restoreBackup runs PBKDF2 + decrypt — off the main thread, or it freezes the UI for the
        // ~1-2s it takes. The result is applied back on Main to update the step state.
        coroutineScope.launch {
            val outcome = withContext(Dispatchers.Default) { runCatching { BackupManager(context).restoreBackup(bytes, words) } }
            step = if (outcome.isSuccess) {
                RestoreStep.DONE
            } else {
                errorMessage = when (outcome.exceptionOrNull()) {
                    is AEADBadTagException -> "Wrong recovery phrase, or the file is corrupted."
                    else -> "Restore failed: ${outcome.exceptionOrNull()?.message ?: "unknown error"}"
                }
                VoronLog.w(TAG, "backup restore failed", outcome.exceptionOrNull())
                RestoreStep.ERROR
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = voronSheetContainerColor()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BackupIcon(Icons.Filled.CloudDownload)
            Spacer(Modifier.height(16.dp))

            when (step) {
                RestoreStep.PICK_FILE -> {
                    Text("Restore from backup", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "This replaces your identity, contacts, and message history on this device with " +
                            "the backup's. Anything only on this device that isn't in the backup will be lost.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    PrimaryActionButton(text = "Choose backup file", onClick = { runCatching { pickFileLauncher.launch(arrayOf("*/*")) } })
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                }

                RestoreStep.ENTER_PHRASE -> {
                    Text("Enter your recovery phrase", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "The 12 words you wrote down when you made this backup, separated by spaces.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = phraseText,
                        onValueChange = { phraseText = it },
                        label = { Text("Recovery phrase") },
                        minLines = 3,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    PrimaryActionButton(
                        text = "Restore this device",
                        enabled = phraseText.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size == 12,
                        onClick = ::attemptRestore,
                    )
                }

                RestoreStep.RESTORING -> {
                    Text("Restoring…", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }

                RestoreStep.DONE -> {
                    Text("Restore complete", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Close Voron completely and reopen it for the restored data to load.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    PrimaryActionButton(text = "OK", onClick = onDismiss)
                }

                RestoreStep.ERROR -> {
                    Text("Couldn't restore", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text(errorMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    PrimaryActionButton(text = "Try again", onClick = { step = RestoreStep.ENTER_PHRASE })
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun BackupIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(
        modifier = Modifier.size(56.dp).background(voronNeutralIconContainerColor(), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = voronNeutralIconTint())
    }
}

@Composable
private fun PrimaryActionButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(26.dp),
        colors = ButtonDefaults.buttonColors(),
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

/** Numbered 3-column grid so the phrase's word ORDER is unambiguous when copied down by hand — a plain wrapped paragraph invites miscounting which word is which. */
@Composable
private fun PhraseGrid(phrase: List<String>) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxWidth().height(160.dp),
    ) {
        items(phrase.size) { index ->
            Card(
                colors = CardDefaults.cardColors(containerColor = voronNeutralIconContainerColor()),
                modifier = Modifier.padding(4.dp).fillMaxWidth(),
            ) {
                Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(
                        "${index + 1}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(20.dp),
                    )
                    Text(phrase[index], style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
