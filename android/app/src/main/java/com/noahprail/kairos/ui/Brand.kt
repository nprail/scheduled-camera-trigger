package com.noahprail.kairos.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/**
 * The Kairos mark: a viewfinder (four corner brackets) holding a timeline, with one signal playhead.
 * Pass `small = true` below 32 dp (heavier strokes, no side ticks).
 */
@Composable
fun KairosMark(size: Dp, modifier: Modifier = Modifier, small: Boolean = size < 32.dp) {
    val ink = MaterialTheme.colorScheme.onSurface
    val signal = MaterialTheme.colorScheme.brand.signal
    Canvas(modifier.size(size).semantics { contentDescription = "Kairos" }) {
        val s = this.size.minDimension / 120f
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        val brackets = Path().apply {
            moveTo(8 * s, 34 * s); lineTo(8 * s, 10 * s); lineTo(32 * s, 10 * s)
            moveTo(88 * s, 10 * s); lineTo(112 * s, 10 * s); lineTo(112 * s, 34 * s)
            moveTo(112 * s, 86 * s); lineTo(112 * s, 110 * s); lineTo(88 * s, 110 * s)
            moveTo(32 * s, 110 * s); lineTo(8 * s, 110 * s); lineTo(8 * s, 86 * s)
        }
        drawPath(brackets, ink, style = Stroke((if (small) 10f else 7f) * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (!small) {
            for (x in floatArrayOf(28f, 44f, 76f, 92f)) {
                drawLine(ink, p(x, 64f), p(x, 90f), strokeWidth = 6 * s, cap = StrokeCap.Round)
            }
        }
        drawLine(signal, p(60f, 42f), p(60f, if (small) 92f else 90f), strokeWidth = (if (small) 10f else 6f) * s, cap = StrokeCap.Round)
        drawCircle(signal, radius = (if (small) 13f else 10f) * s, center = p(60f, 31f))
    }
}

/** The lowercase wordmark: Space Grotesk SemiBold, -0.03em tracking. */
@Composable
fun Wordmark(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Text(
        "kairos",
        modifier = modifier,
        color = color,
        style = MaterialTheme.typography.headlineMedium.copy(
            fontFamily = DisplayFamily,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.03).em,
        ),
    )
}

/** Mark beside wordmark. The gap is 0.3 of the mark's height. */
@Composable
fun KairosLockup(modifier: Modifier = Modifier, markSize: Dp = 32.dp) {
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = "Kairos" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(markSize * 0.3f),
    ) {
        KairosMark(markSize)
        Wordmark()
    }
}
