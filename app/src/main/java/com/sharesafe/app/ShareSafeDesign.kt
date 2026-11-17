package com.sharesafe.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF102923)
private val Forest = Color(0xFF145C48)
private val Leaf = Color(0xFFDDF68D)
private val Paper = Color(0xFFF7F8F2)
private val Rule = Color(0xFFDADFD2)

@Composable
internal fun ShareSafeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Forest,
            onPrimary = Color.White,
            primaryContainer = Leaf,
            onPrimaryContainer = Ink,
            secondary = Color(0xFF436657),
            secondaryContainer = Color(0xFFE4EFE5),
            onSecondaryContainer = Ink,
            background = Paper,
            onBackground = Ink,
            surface = Color.White,
            onSurface = Ink,
            surfaceVariant = Color(0xFFECF0E7),
            onSurfaceVariant = Color(0xFF496056),
            outline = Color(0xFF708477),
            outlineVariant = Rule,
        ),
        typography = Typography().run {
            copy(
                displaySmall = displaySmall.copy(fontFamily = FontFamily.Serif, fontSize = 43.sp, lineHeight = 45.sp),
                headlineMedium = headlineMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium),
                titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
                titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
                labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
            )
        },
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(30.dp),
        ),
        content = content,
    )
}

@Composable
internal fun ShareSafeHeader() {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(36.dp).background(Ink, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            TransferMark("lock", Leaf, Modifier.size(20.dp))
        }
        Text("ShareSafe", style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
internal fun ShareSafeHome(onSend: () -> Unit, onReceive: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(
                Brush.linearGradient(listOf(Ink, Color(0xFF194534))),
                RoundedCornerShape(28.dp),
            ),
        ) {
            VaultArtwork(Modifier.align(Alignment.TopEnd).size(124.dp))
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Share files\nsecurely", color = Color.White, style = MaterialTheme.typography.displaySmall)
                Text(
                    "Send a document or audio file, or open a share sent to you.",
                    color = Color(0xFFDCE9D9), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.widthIn(max = 270.dp),
                )
            }
        }

        TransferCard("Send a file", "Choose a file and set its access rules.", "send", true, onSend)
        TransferCard("Receive a file", "Open an invitation and verify access.", "receive", false, onReceive)
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("PDF", "DOCX", "MP3", "WAV").forEach { type ->
                Surface(
                    shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        type, style = MaterialTheme.typography.labelMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 3.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TransferCard(title: String, description: String, kind: String, featured: Boolean, action: () -> Unit) {
    val container = if (featured) Leaf else Color.White
    Card(
        onClick = action,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = Ink),
        border = if (featured) null else androidx.compose.foundation.BorderStroke(1.dp, Rule),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(44.dp).background(if (featured) Ink else Paper, RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center,
            ) {
                TransferMark(kind, if (featured) Leaf else Forest, Modifier.size(23.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF405B4E))
            }
            TransferMark("forward", Ink, Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun FlowPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
internal fun FlowHeading(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun VaultArtwork(modifier: Modifier) {
    Canvas(modifier) {
        val c = Offset(size.width * .71f, size.height * .5f)
        val faint = Leaf.copy(alpha = .18f)
        for (radius in listOf(.32f, .44f, .57f)) {
            drawCircle(faint, radius = size.width * radius, center = c, style = Stroke(1.dp.toPx()))
        }
        drawLine(faint, Offset(0f, c.y), Offset(size.width, c.y), strokeWidth = 1.dp.toPx())
        drawLine(faint, Offset(c.x, 0f), Offset(c.x, size.height), strokeWidth = 1.dp.toPx())
        drawCircle(Leaf, radius = 3.dp.toPx(), center = Offset(c.x - size.width * .44f, c.y))
        drawCircle(Leaf, radius = 2.dp.toPx(), center = Offset(c.x, c.y + size.width * .32f))
    }
}

@Composable
private fun TransferMark(kind: String, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val unit = size.minDimension / 24f
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, point(x1, y1), point(x2, y2), strokeWidth = 1.8f * unit, cap = StrokeCap.Round)
        if (kind == "lock") {
            drawArc(color, 180f, 180f, false, point(7f, 3f), Size(10f * unit, 10f * unit), style = Stroke(1.8f * unit))
            drawRoundRect(color, point(5f, 9f), Size(14f * unit, 12f * unit), CornerRadius(3f * unit), style = Stroke(1.8f * unit))
            line(12f, 14f, 12f, 16f)
        } else if (kind == "forward") {
            line(5f, 12f, 18f, 12f)
            line(13f, 7f, 18f, 12f)
            line(13f, 17f, 18f, 12f)
        } else {
            line(12f, 4f, 12f, 15f)
            val tip = if (kind == "send") 4f else 15f
            val shoulders = if (kind == "send") 9f else 10f
            line(7f, shoulders, 12f, tip)
            line(17f, shoulders, 12f, tip)
            line(4f, 15f, 4f, 20f)
            line(4f, 20f, 20f, 20f)
            line(20f, 20f, 20f, 15f)
        }
    }
}
