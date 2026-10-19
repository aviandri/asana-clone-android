package com.mekari.project.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** The web's petal colours (brand magenta and purple). */
private val PetalColors = listOf(0xFFE0257F, 0xFFFF5FA2, 0xFF651FFF, 0xFF8F5CFF, 0xFFB0136B, 0xFFFFD1E6).map {
    Color(it)
}
private const val PETALS = 14
private const val DURATION_MS = 1200

/**
 * The occasional celebration: 14 petals burst from [CelebrationOrigin.last] (or the upper middle) when
 * [eventId] changes. It's a full-screen overlay with no pointer input, so taps go straight through to
 * the app below. Nothing is drawn with animations off.
 *
 * Pass the event's id as it is (CompletionFeedback.lastEvent): this composable remembers which one it
 * played, so a rotation or coming back doesn't replay it. The effect's key must stay put while the burst
 * runs. Nulling it right after starting (the old "already shown" dedupe outside) cancelled the animation
 * at progress 0 and froze all 14 petals at the checkbox: the stray dot of QA A2. And only a running burst
 * draws, so a cancelled one can't freeze on screen again.
 */
@Composable
fun PetalBurst(eventId: Int?, modifier: Modifier = Modifier) {
    val enabled = animationsEnabled(LocalContext.current)
    // Saved, and seeded with the event already there, so only events after this appears celebrate.
    var played by rememberSaveable { mutableStateOf(eventId) }
    val progress = remember { Animatable(1f) }
    var origin by remember { mutableStateOf<Offset?>(null) }
    var running by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(eventId) {
        if (eventId == null || eventId == played) return@LaunchedEffect
        played = eventId
        if (!enabled) return@LaunchedEffect
        origin = CelebrationOrigin.last
        running = eventId
        try {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(DURATION_MS, easing = LinearEasing))
        } finally {
            // A newer burst may have taken over meanwhile; only clear our own.
            if (running == eventId) running = null
        }
    }
    if (running == null || progress.value >= 1f) return
    Canvas(modifier.fillMaxSize().testTag("celebration.burst")) {
        val t = progress.value
        val eased = 1f - (1f - t).pow(3)
        val centre = origin ?: Offset(size.width / 2, size.height / 3)
        val unit = density
        for (i in 0 until PETALS) {
            val angle = i.toDouble() / PETALS * 2 * Math.PI + (i % 2) * 0.2
            val distance = (80 + (i * 37) % 60) * unit
            val x = centre.x + (cos(angle) * distance * eased).toFloat()
            val y = centre.y + (sin(angle) * distance * eased).toFloat() + 40 * unit * t * t
            val spin = (if (i % 2 == 0) -1 else 1) * (120 + i * 12) * eased
            val s = (0.5f + 0.7f * eased) * unit
            translate(x, y) {
                rotate(spin, pivot = Offset.Zero) {
                    scale(s, s, pivot = Offset.Zero) {
                        drawPath(PetalPath, PetalColors[i % PetalColors.size].copy(alpha = 1f - t))
                    }
                }
            }
        }
    }
}

/** The web's petal (viewBox 0 0 10 14), centred on the origin. */
private val PetalPath = Path().apply {
    moveTo(0f, -7f)
    cubicTo(2.8f, -4.4f, 4.6f, -1f, 3.6f, 2.4f)
    cubicTo(2.9f, 4.9f, 1.4f, 6.4f, 0f, 7f)
    cubicTo(-1.4f, 6.4f, -2.9f, 4.9f, -3.6f, 2.4f)
    cubicTo(-4.6f, -1f, -2.8f, -4.4f, 0f, -7f)
    close()
}
