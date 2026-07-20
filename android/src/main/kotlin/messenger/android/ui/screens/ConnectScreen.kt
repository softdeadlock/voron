package messenger.android.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import messenger.android.data.ConnectionState
import messenger.android.ui.VoronLogo
import messenger.android.ui.theme.voronEncryptedColor

/** No manual "Connect" step: the app has one fixed relay, so it just dials in on launch and this screen narrates that with an animated loader, retrying forever under the hood (see ConnectionManager). */
@Composable
fun ConnectScreen(
    connection: ConnectionState,
    onConnect: () -> Unit,
) {
    LaunchedEffect(Unit) { onConnect() }
    val succeeded = connection is ConnectionState.Connected

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LoadingMark(succeeded)
        Spacer(Modifier.height(28.dp))
        Text(
            "Voron",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                succeeded -> "Connected"
                connection is ConnectionState.Failed -> "Reconnecting…"
                else -> "Connecting to the network…"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LoadingMark(succeeded: Boolean) {
    val transition = rememberInfiniteTransition(label = "connect-loading")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
        label = "rotation",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
        label = "pulse",
    )

    val ringStart = MaterialTheme.colorScheme.primary
    val ringEnd = MaterialTheme.colorScheme.secondary
    val logoTint = MaterialTheme.colorScheme.onBackground
    val successColor = voronEncryptedColor()

    Box(modifier = Modifier.size(120.dp), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = !succeeded,
            // Explicit calm fade-in: this appears the instant the Connect screen is first
            // composed (e.g. right after unlocking), so without an explicit `enter` here,
            // AnimatedVisibility's default (fade + expand-from-a-point) made the ring/logo look
            // like it was "flying in" from nothing instead of just quietly fading into view.
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(220)) + scaleOut(targetScale = 0.6f, animationSpec = tween(220)),
        ) {
            Box(modifier = Modifier.size(120.dp), contentAlignment = Alignment.Center) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { rotationZ = rotation },
                ) {
                    val strokeWidth = 5.dp.toPx()
                    drawArc(
                        brush = Brush.sweepGradient(
                            listOf(ringStart.copy(alpha = 0f), ringStart, ringEnd, ringEnd.copy(alpha = 0f)),
                        ),
                        startAngle = 0f,
                        sweepAngle = 300f,
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                        topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
                        size = Size(size.width - strokeWidth, size.height - strokeWidth),
                    )
                }
                VoronLogo(
                    size = 48.dp,
                    tint = logoTint,
                    modifier = Modifier.graphicsLayer { scaleX = pulse; scaleY = pulse },
                )
            }
        }
        AnimatedVisibility(
            visible = succeeded,
            // A plain tween instead of the previous bouncy low-stiffness spring: the overshoot
            // read as the checkmark "flying in" rather than smoothly appearing.
            enter = fadeIn(tween(300, delayMillis = 100)) +
                scaleIn(initialScale = 0.7f, animationSpec = tween(300, delayMillis = 100, easing = FastOutSlowInEasing)),
        ) {
            Icon(
                Icons.Filled.GppGood,
                contentDescription = "Connected",
                tint = successColor,
                modifier = Modifier.size(64.dp),
            )
        }
    }
}
