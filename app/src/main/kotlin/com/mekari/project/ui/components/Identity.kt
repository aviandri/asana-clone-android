package com.mekari.project.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** "Alice Johnson" → "AJ"; one word → its first letter; nothing → "?". */
fun initials(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(1).uppercase()
        else -> (words.first().take(1) + words.last().take(1)).uppercase()
    }
}

/** Readable text colour on a coloured tile or avatar. */
fun contentColorOn(background: Color): Color {
    val light = background.luminance() > 0.6f
    return if (light) Color.Black.copy(alpha = 0.85f) else Color.White
}

/** A round avatar with initials on the user's colour (purely decorative; the name is said elsewhere). */
@Composable
fun Avatar(name: String, color: String, size: Dp = 28.dp, modifier: Modifier = Modifier) {
    val background = parseHexColor(color)
    Box(
        modifier.size(size).background(background, CircleShape).clearAndSetSemantics { },
        contentAlignment = Alignment.Center
    ) {
        Text(
            initials(name),
            color = contentColorOn(background),
            fontSize = (size.value * 0.4f).sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** A rounded-square workspace tile with its initial, as on the web and Mac. */
@Composable
fun WorkspaceTile(name: String, color: String, size: Dp = 28.dp, modifier: Modifier = Modifier) {
    val background = parseHexColor(color)
    Box(
        modifier.size(size).background(background, RoundedCornerShape(size * 0.25f)).clearAndSetSemantics { },
        contentAlignment = Alignment.Center
    ) {
        Text(
            name.trim().take(1).uppercase().ifEmpty { "?" },
            color = contentColorOn(background),
            fontSize = (size.value * 0.5f).sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/** The "Guest" chip for guest workspaces and guest people (W8). */
@Composable
fun GuestChip(modifier: Modifier = Modifier) {
    Surface(
        color = Color(0x26FF9800),
        contentColor = Color(0xFFE65100),
        shape = RoundedCornerShape(50),
        modifier = modifier
    ) {
        Text(
            "Guest",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
        )
    }
}
