package com.battlesbudz.jarvis.v2.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlin.math.*

@Composable
internal fun VoiceOrb(phase: String, level: Float) {
    val color = MaterialTheme.colorScheme.primary
    val motion by rememberInfiniteTransition(label = "voice circle").animateFloat(
        initialValue = 0f, targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "orbit")
    val amplitude by animateFloatAsState(level, tween(90), label = "voice amplitude")
    Box(Modifier.fillMaxWidth().height(220.dp).semantics { contentDescription = "Voice call: $phase" },
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(210.dp)) {
            val radius = size.minDimension * .32f
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = .16f), color.copy(alpha = 0f))), radius * 1.55f)
            drawCircle(color.copy(alpha = .25f), radius, style = Stroke(1.dp.toPx()))
            repeat(80) { i ->
                val angle = i * 2 * PI / 80
                val active = phase != "Ready"
                val wave = (sin(angle * 5 + motion) + 1).toFloat() / 2
                val energy = if (phase == "Speaking") amplitude else if (active) .15f else .025f
                val length = 3.dp.toPx() + (8 + 30 * wave).dp.toPx() * energy
                val start = radius + 5.dp.toPx()
                drawLine(color.copy(alpha = if (active) .9f else .4f),
                    Offset(center.x + cos(angle).toFloat() * start, center.y + sin(angle).toFloat() * start),
                    Offset(center.x + cos(angle).toFloat() * (start + length), center.y + sin(angle).toFloat() * (start + length)),
                    strokeWidth = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
        Text(phase, style = MaterialTheme.typography.titleLarge, color = color)
    }
}

@Composable
internal fun VoiceCaption(text: String, speaker: String) {
    Column(Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 180.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        if (text.isNotBlank()) {
            Text(speaker, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            val split = text.lastIndexOf(' ')
            Text(buildAnnotatedString {
                if (split >= 0) append(text.substring(0, split + 1))
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)) {
                    append(text.substring(split + 1))
                }
            }, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center, maxLines = 6)
        } else Text("Your voice calls stay on this phone.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
