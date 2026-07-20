package messenger.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A small abstract raven silhouette: a body with two swept wing/tail
 * strokes and a beak, echoing the landing page's bird mark without trying
 * to reproduce it pixel-for-pixel.
 */
@Composable
fun VoronLogo(modifier: Modifier = Modifier, size: Dp = 32.dp, tint: Color = Color.Black) {
    Canvas(modifier = modifier.size(size)) {
        drawRaven(tint)
    }
}

/** Exposed (not `private`) so [Avatar]'s avatar-glyph picker can reuse the exact same silhouette. */
internal fun DrawScope.drawRaven(tint: Color) {
    val w = size.width
    val h = size.height

    val body = Path().apply {
        moveTo(w * 0.30f, h * 0.62f)
        cubicTo(w * 0.30f, h * 0.40f, w * 0.48f, h * 0.28f, w * 0.62f, h * 0.34f)
        cubicTo(w * 0.72f, h * 0.38f, w * 0.78f, h * 0.48f, w * 0.74f, h * 0.58f)
        cubicTo(w * 0.70f, h * 0.68f, w * 0.56f, h * 0.72f, w * 0.44f, h * 0.68f)
        cubicTo(w * 0.36f, h * 0.65f, w * 0.30f, h * 0.66f, w * 0.30f, h * 0.62f)
        close()
    }
    drawPath(body, color = tint)

    // Beak
    val beak = Path().apply {
        moveTo(w * 0.74f, h * 0.46f)
        lineTo(w * 0.90f, h * 0.50f)
        lineTo(w * 0.73f, h * 0.56f)
        close()
    }
    drawPath(beak, color = tint)

    // Tail sweep
    val tail = Path().apply {
        moveTo(w * 0.34f, h * 0.64f)
        cubicTo(w * 0.18f, h * 0.70f, w * 0.08f, h * 0.80f, w * 0.04f, h * 0.90f)
        cubicTo(w * 0.16f, h * 0.86f, w * 0.28f, h * 0.78f, w * 0.38f, h * 0.70f)
        close()
    }
    drawPath(tail, color = tint)

    // Eye
    drawCircle(color = Color.White, radius = w * 0.03f, center = Offset(w * 0.58f, h * 0.42f))
}
