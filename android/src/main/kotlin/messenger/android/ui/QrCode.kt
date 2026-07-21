package messenger.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import messenger.android.ui.theme.voronAccentGradient
import messenger.android.ui.theme.VoronViolet

/** Payload prefix identifying a Voron contact QR — the scanner accepts both `voron:<hex>` and a bare hex key. */
const val QR_KEY_PREFIX = "voron:"

/** Extracts a 64-hex-char device key from scanned QR [content] (with or without [QR_KEY_PREFIX]), or null if it isn't one. */
fun parseContactQr(content: String): String? {
    val candidate = content.removePrefix(QR_KEY_PREFIX).trim()
    val isHexKey = candidate.length == 64 && candidate.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
    return if (isHexKey) candidate.lowercase() else null
}

/**
 * Renders [content] as a QR in a white rounded card, framed by a thin violet→lavender gradient
 * border (the app's signature accent, echoing avatars/bubbles/FAB) and a soft matching shadow so
 * it reads as a deliberate, branded card rather than a plain scan target. Always
 * dark-modules-on-white regardless of theme — scanners want that contrast, and inverting in dark
 * mode measurably hurts detection.
 */
@Composable
fun QrCodeCard(content: String, size: Dp = 220.dp, modifier: Modifier = Modifier) {
    val bitmap = remember(content) { renderQr(content) }
    Box(
        modifier = modifier
            .shadow(12.dp, RoundedCornerShape(24.dp), ambientColor = VoronViolet.copy(alpha = 0.4f), spotColor = VoronViolet.copy(alpha = 0.4f))
            .background(Brush.linearGradient(voronAccentGradient()), RoundedCornerShape(24.dp))
            .padding(3.dp)
            .background(Color.White, RoundedCornerShape(21.dp))
            .padding(16.dp),
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "QR code",
            contentScale = ContentScale.Fit,
            // Nearest-neighbor scaling: the bitmap is one pixel per QR module, and default
            // bilinear filtering would smear the crisp squares into a gray blur.
            filterQuality = FilterQuality.None,
            modifier = Modifier.size(size),
        )
    }
}

/** One pixel per module — tiny bitmap, scaled up losslessly by [FilterQuality.None] at draw time. */
private fun renderQr(content: String): Bitmap {
    val hints = mapOf(
        EncodeHintType.MARGIN to 0,
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
    val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            bitmap.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    return bitmap
}
