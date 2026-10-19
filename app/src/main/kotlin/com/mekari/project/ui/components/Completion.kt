package com.mekari.project.ui.components

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Completion settings the app provides from DataStore (SPEC-ANDROID §5: sound off by default). */
data class CompletionPrefs(val playSound: Boolean = false)

val LocalCompletionPrefs = compositionLocalOf { CompletionPrefs() }

/** False when the user turned animations off ("Remove animations": animator duration scale 0). */
fun animationsEnabled(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

/**
 * The complete / reopen checkbox on every task surface.
 *
 * - The whole 48 dp box is the touch target (a Mac lesson: an open ring with a hollow middle let taps fall
 *   through to the row).
 * - Read-only boxes swallow taps, so they never select or open the row underneath.
 * - The look follows [checked], so a completion the server rejects (the cache rolls it back) animates back
 *   by itself.
 */
@Composable
fun CompletionCheckbox(
    checked: Boolean,
    title: String,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    markSize: Dp = 22.dp,
    testTag: String? = null
) {
    val view = LocalView.current
    val prefs = LocalCompletionPrefs.current
    var center by remember { mutableStateOf<Offset?>(null) }
    val label = if (checked) "Mark $title incomplete" else "Mark $title complete"
    val base = modifier
        .size(48.dp)
        .onGloballyPositioned { center = it.boundsInRoot().center }
        .let { if (testTag != null) it.testTag(testTag) else it }
    val interactive = if (enabled) {
        base.toggleable(value = checked, role = Role.Checkbox) {
            CompletionEffects.play(view, completing = !checked, title = title, sound = prefs.playSound)
            if (!checked) center?.let { CelebrationOrigin.last = it }
            onToggle()
        }
    } else {
        // Consume the tap so the row behind doesn't get it; still described for TalkBack.
        base.pointerInput(Unit) { detectTapGestures { } }
            .toggleable(value = checked, enabled = false, role = Role.Checkbox) { }
    }
    Box(interactive.semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        CompletionMark(checked = checked, size = markSize)
    }
}

/** The mark: an outlined ring that fills with the accent colour while a checkmark draws in, with a pop. */
@Composable
fun CompletionMark(checked: Boolean, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    val animate = animationsEnabled(LocalContext.current)
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = if (animate) 220 else 120),
        label = "check"
    )
    val pop = remember { Animatable(1f) }
    var last by remember { mutableStateOf(checked) }
    LaunchedEffect(checked) {
        if (checked != last && animate) {
            pop.animateTo(1.15f, spring(stiffness = Spring.StiffnessHigh))
            pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
        last = checked
    }
    val accent = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val onAccent = MaterialTheme.colorScheme.onPrimary
    Canvas(modifier.size(size).scale(pop.value)) {
        val stroke = this.size.minDimension * 0.08f
        val radius = this.size.minDimension / 2
        // Nothing is drawn at the ends of the animation but the ring or the full mark: a near-zero fill or
        // check must not leave a speck behind (QA A2).
        if (progress < 1f) {
            drawCircle(outline.copy(alpha = 1f - progress), radius = radius - stroke / 2, style = Stroke(stroke))
        }
        if (progress <= 0f) return@Canvas
        drawCircle(accent.copy(alpha = progress), radius = radius)
        val w = this.size.width
        val h = this.size.height
        val check = Path().apply {
            moveTo(w * 0.28f, h * 0.53f)
            lineTo(w * 0.44f, h * 0.68f)
            lineTo(w * 0.73f, h * 0.36f)
        }
        // Draw the first `progress` of the check: the stroke goes down, then up.
        val measure = PathMeasure().apply { setPath(check, false) }
        val checkStroke = this.size.minDimension * 0.11f
        val length = if (animate) measure.length * progress else measure.length
        // A round cap on a near-zero segment is a dot: start the check only once it's longer than it's wide.
        if (length < checkStroke) return@Canvas
        val drawn = Path()
        measure.getSegment(0f, length, drawn, true)
        drawPath(
            drawn,
            onAccent.copy(alpha = if (animate) 1f else progress),
            style = Stroke(width = checkStroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

/** A one-line task title whose strike-through sweeps in from the start as it completes, dimming the text. */
@Composable
fun CompletionTitle(
    title: String,
    completed: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge
) {
    val animate = animationsEnabled(LocalContext.current)
    val sweep by animateFloatAsState(
        targetValue = if (completed) 1f else 0f,
        animationSpec = tween(durationMillis = if (animate) 200 else 120),
        label = "strike"
    )
    val colors = MaterialTheme.colorScheme
    val color by animateColorAsState(
        targetValue = if (completed) colors.onSurfaceVariant else colors.onSurface,
        animationSpec = tween(200),
        label = "titleColor"
    )
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = title,
        style = style,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.drawWithContent {
            drawContent()
            if (sweep > 0f) {
                val y = size.height / 2
                val end = if (animate) size.width * sweep else size.width
                drawLine(
                    lineColor.copy(alpha = if (animate) 1f else sweep),
                    Offset(0f, y),
                    Offset(end, y),
                    strokeWidth = 1.dp.toPx()
                )
            }
        }
    )
}

/** Haptic, the optional chime ([CompletionSound]) and the TalkBack announcement for a checkbox tap. */
object CompletionEffects {
    fun play(view: View, completing: Boolean, title: String, sound: Boolean) {
        val confirm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val haptic = if (confirm) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK
        view.performHapticFeedback(haptic)
        if (completing && sound) CompletionSound.play(view.context)
        @Suppress("DEPRECATION")
        view.announceForAccessibility(if (completing) "Completed $title" else "Marked $title incomplete")
    }

    /** A completion the server rejected (CompletionFeedback.rolledBack). */
    fun announceRollback(view: View, title: String) {
        @Suppress("DEPRECATION")
        view.announceForAccessibility("Couldn't complete $title")
    }
}

/**
 * How task rows enter and leave a lazy list (the insert after an add; the collapse after the completion
 * linger). Timed fades rather than the default springs, so a leaving row is gone at a known moment instead of
 * on a long, nearly invisible tail.
 */
fun LazyItemScope.taskRowAnimation(): Modifier = Modifier.animateItem(fadeInSpec = tween(150), fadeOutSpec = tween(180))

/** Where the last completion tap was (root coordinates), so a celebration bursts from the checkbox. */
object CelebrationOrigin {
    var last: Offset? = null
}

/** Parses "#RRGGBB" (avatar / project / workspace colours); grey for anything else. */
fun parseHexColor(hex: String): Color {
    val digits = hex.removePrefix("#")
    if (digits.length != 6) return Color(0xFF9E9E9E)
    return digits.toLongOrNull(16)?.let { Color(0xFF000000 or it) } ?: Color(0xFF9E9E9E)
}
