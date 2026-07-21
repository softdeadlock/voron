package messenger.android.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import messenger.android.data.StickerId
import messenger.android.ui.theme.voronSheetContainerColor

/**
 * A grid of every bundled sticker, tap-to-send-and-close — same "sheet closes, then the action
 * fires" pattern as [AttachSheet] so there's no visible fight between the sheet's own dismiss
 * animation and whatever happens after. Founder-only entries (see [StickerId.founderOnly]) are
 * simply omitted for a non-founder install rather than shown locked/greyed — this build's icon set
 * genuinely doesn't include them, matching how [StickerId.fromWireValue] degrades a received one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StickerPickerSheet(
    isFounder: Boolean,
    onDismiss: () -> Unit,
    onPick: (StickerId) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun pickThenDismiss(sticker: StickerId) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            onPick(sticker)
        }
    }
    val stickers = StickerId.entries.filter { !it.founderOnly || isFounder }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = voronSheetContainerColor(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(
                "Stickers",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp).padding(bottom = 20.dp),
            ) {
                items(stickers) { sticker ->
                    Column(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { pickThenDismiss(sticker) }
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Image(
                            painter = painterResource(sticker.drawableRes),
                            contentDescription = sticker.name,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                        if (sticker.founderOnly) {
                            Icon(
                                Icons.Filled.Star,
                                contentDescription = "Founder exclusive",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
