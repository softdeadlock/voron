package messenger.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import messenger.android.data.AvatarIconId
import messenger.android.ui.theme.VoronAvatarGradient

/** A gradient-filled circle with the contact's first initial (or a chosen raven/bird glyph), matching the landing page avatars. */
@Composable
fun Avatar(label: String, modifier: Modifier = Modifier, size: Dp = 44.dp, isDrafts: Boolean = false, iconId: AvatarIconId? = null) {
    Box(
        modifier = modifier
            .size(size)
            .background(Brush.linearGradient(VoronAvatarGradient), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isDrafts -> Icon(
                Icons.Filled.EditNote,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(size * 0.62f),
            )
            iconId != null -> Canvas(modifier = Modifier.size(size * 0.64f)) { drawAvatarGlyph(iconId) }
            else -> Text(
                text = label.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                color = Color.White,
                fontSize = (size.value / 2.2).sp,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/**
 * Hand-drawn, raven/bird-themed avatar glyphs — deliberately not stock Material icons, to match
 * [VoronLogo]'s own silhouette style. Kept bold and simple on purpose: a couple of big filled
 * shapes read cleanly at avatar size, where fine linework (thin strokes, multiple small details)
 * just turns to visual noise.
 */
fun DrawScope.drawAvatarGlyph(iconId: AvatarIconId) {
    when (iconId) {
        AvatarIconId.RAVEN -> drawRavenGlyph()
        AvatarIconId.WING -> drawWingGlyph()
        AvatarIconId.FEATHER -> drawFeatherGlyph()
        AvatarIconId.FLOCK -> drawFlockGlyph()
        AvatarIconId.MOON -> drawMoonGlyph()
        AvatarIconId.EYE -> drawEyeGlyph()
    }
}

/** A bold raven head + beak, not the full-body [VoronLogo] mark — its fine tail/wing lines wash out at avatar size. */
private fun DrawScope.drawRavenGlyph() {
    val w = size.width
    val h = size.height
    val head = Path().apply {
        moveTo(w * 0.26f, h * 0.60f)
        cubicTo(w * 0.18f, h * 0.36f, w * 0.40f, h * 0.16f, w * 0.62f, h * 0.20f)
        cubicTo(w * 0.82f, h * 0.24f, w * 0.92f, h * 0.46f, w * 0.80f, h * 0.64f)
        cubicTo(w * 0.70f, h * 0.80f, w * 0.46f, h * 0.86f, w * 0.32f, h * 0.76f)
        cubicTo(w * 0.26f, h * 0.72f, w * 0.24f, h * 0.66f, w * 0.26f, h * 0.60f)
        close()
    }
    drawPath(head, color = Color.White)
    val beak = Path().apply {
        moveTo(w * 0.78f, h * 0.40f)
        lineTo(w * 0.97f, h * 0.47f)
        lineTo(w * 0.76f, h * 0.56f)
        close()
    }
    drawPath(beak, color = Color.White)
    drawCircle(color = GlyphAccent, radius = w * 0.055f, center = Offset(w * 0.56f, h * 0.38f))
}

/** A single bold swept shape — one wing in flight, tapering to a point at each end. */
private fun DrawScope.drawWingGlyph() {
    val w = size.width
    val h = size.height
    val wing = Path().apply {
        moveTo(w * 0.08f, h * 0.88f)
        cubicTo(w * 0.06f, h * 0.48f, w * 0.28f, h * 0.14f, w * 0.92f, h * 0.08f)
        cubicTo(w * 0.56f, h * 0.28f, w * 0.34f, h * 0.54f, w * 0.28f, h * 0.86f)
        cubicTo(w * 0.20f, h * 0.90f, w * 0.13f, h * 0.90f, w * 0.08f, h * 0.88f)
        close()
    }
    drawPath(wing, color = Color.White)
}

/** A clean pointed feather vane with a single bold spine — no fussy barb lines. */
private fun DrawScope.drawFeatherGlyph() {
    val w = size.width
    val h = size.height
    val plume = Path().apply {
        moveTo(w * 0.5f, h * 0.06f)
        cubicTo(w * 0.84f, h * 0.24f, w * 0.84f, h * 0.64f, w * 0.5f, h * 0.95f)
        cubicTo(w * 0.16f, h * 0.64f, w * 0.16f, h * 0.24f, w * 0.5f, h * 0.06f)
        close()
    }
    drawPath(plume, color = Color.White)
    drawLine(
        color = GlyphAccent,
        start = Offset(w * 0.5f, h * 0.16f),
        end = Offset(w * 0.5f, h * 0.86f),
        strokeWidth = w * 0.055f,
        cap = StrokeCap.Round,
    )
}

/** Two bold, rounded-stroke birds — fewer and bigger than a literal flock reads better this small. */
private fun DrawScope.drawFlockGlyph() {
    val w = size.width
    val h = size.height

    fun bird(cx: Float, cy: Float, spread: Float, dip: Float) = Path().apply {
        moveTo(cx - spread, cy)
        quadraticTo(cx - spread * 0.35f, cy - dip, cx, cy)
        quadraticTo(cx + spread * 0.35f, cy - dip, cx + spread, cy)
    }

    drawPath(
        bird(w * 0.54f, h * 0.38f, w * 0.36f, h * 0.20f),
        color = Color.White,
        style = Stroke(width = w * 0.10f, cap = StrokeCap.Round),
    )
    drawPath(
        bird(w * 0.36f, h * 0.68f, w * 0.24f, h * 0.14f),
        color = Color.White,
        style = Stroke(width = w * 0.085f, cap = StrokeCap.Round),
    )
}

/** A crescent moon: a full disc with a second, offset disc cut out of it via an even-odd fill. */
private fun DrawScope.drawMoonGlyph() {
    val w = size.width
    val h = size.height
    val moon = Path().apply {
        fillType = PathFillType.EvenOdd
        addOval(Rect(center = Offset(w * 0.44f, h * 0.5f), radius = w * 0.36f))
        addOval(Rect(center = Offset(w * 0.62f, h * 0.38f), radius = w * 0.30f))
    }
    drawPath(moon, color = Color.White)
}

/** A watchful almond eye with a solid pupil. */
private fun DrawScope.drawEyeGlyph() {
    val w = size.width
    val h = size.height
    val almond = Path().apply {
        moveTo(w * 0.10f, h * 0.5f)
        quadraticTo(w * 0.5f, h * 0.20f, w * 0.90f, h * 0.5f)
        quadraticTo(w * 0.5f, h * 0.80f, w * 0.10f, h * 0.5f)
        close()
    }
    drawPath(almond, color = Color.White)
    drawCircle(color = GlyphAccent, radius = w * 0.13f, center = Offset(w * 0.5f, h * 0.5f))
}

/** A dark accent for the small detail inside an otherwise-solid-white glyph (pupil, eye dot, spine) — needs to read against white regardless of theme, so it isn't tied to [MaterialTheme]. Same tone the original eye glyph used, kept unchanged. */
private val GlyphAccent = Color(0x552A2A2A)
