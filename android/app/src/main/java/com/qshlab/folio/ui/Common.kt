package com.qshlab.folio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Accent = Color(0xFF3F51D1)
val RecRed = Color(0xFFD23B2E)
val SPEAKER_COLORS = listOf(0xFF2F7D6B, 0xFFB5603B, 0xFF4A6FB5, 0xFF9A5AA8, 0xFFB08A2E, 0xFF3F8FA6, 0xFFA8485F, 0xFF6B7D3A).map { Color(it) }
fun speakerColor(spk: Int?) = if (spk == null) Color.Gray else SPEAKER_COLORS[spk % SPEAKER_COLORS.size]

@Composable
fun FolioTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFF8B9BFF), onPrimary = Color(0xFF0F1430), primaryContainer = Color(0xFF232A4A), onPrimaryContainer = Color(0xFFDDE2FF),
        background = Color(0xFF121419), surface = Color(0xFF121419), surfaceContainer = Color(0xFF1A1D24), surfaceContainerHigh = Color(0xFF20242C),
        surfaceContainerLow = Color(0xFF16181E), onSurface = Color(0xFFE5E8EE), onSurfaceVariant = Color(0xFF949CAD), outlineVariant = Color(0xFF2A2E38),
        error = Color(0xFFF0907B),
    ) else lightColorScheme(
        primary = Accent, onPrimary = Color.White, primaryContainer = Color(0xFFE8EBFB), onPrimaryContainer = Accent,
        background = Color(0xFFF6F7F9), surface = Color(0xFFF6F7F9), surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFEEF0F4),
        surfaceContainerLow = Color(0xFFFAFBFC), onSurface = Color(0xFF1B2130), onSurfaceVariant = Color(0xFF626B7D), outlineVariant = Color(0xFFE1E5EC),
        error = Color(0xFFC2412D),
    )
    MaterialTheme(colorScheme = colors, content = content)
}

/** Titles: the same clean sans-serif as the Mac app (Folio used a book-like serif before). */
val Heading = FontFamily.Default

fun clock(s: Double): String {
    val h = (s / 3600).toInt(); val m = ((s % 3600) / 60).toInt(); val x = (s % 60).toInt()
    return if (h > 0) "%d:%02d:%02d".format(h, m, x) else "%d:%02d".format(m, x)
}

fun whenText(t: Double): String = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date((t * 1000).toLong()))

fun initials(n: String) = n.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }

val STEP_LABEL = mapOf("updating state" to "Updating", "stopped" to "Stopped", "listening again" to "Listening again",
    "checking text" to "Checking text", "summarizing" to "Summarizing", "recording" to "Recording", "queued" to "Waiting",
    "decoding" to "Reading audio", "transcribing" to "Transcribing", "speakers" to "Finding speakers", "processing" to "Processing")

@Composable
fun Badge(text: String, error: Boolean = false) {
    Text(text, Modifier.clip(RoundedCornerShape(99.dp))
        .background(if (error) MaterialTheme.colorScheme.error.copy(alpha = 0.15f) else MaterialTheme.colorScheme.primaryContainer)
        .padding(horizontal = 8.dp, vertical = 2.dp),
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, fontSize = 12.sp)
}

@Composable
fun Dot(color: Color, size: Int = 8) = Box(Modifier.size(size.dp).clip(CircleShape).background(color))

@Composable
fun Avatar(name: String) {
    Box(Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        Text(initials(name), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier.padding(top = 20.dp, bottom = 6.dp), fontFamily = Heading, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)

@Composable
fun Hint(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    Text(text, modifier, color = color, style = MaterialTheme.typography.bodySmall)

/** Just enough Markdown for state.md: headings, bullets, bold. */
@Composable
fun Markdownish(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (raw in text.lines()) {
            val line = raw.trimEnd()
            when {
                line.isBlank() -> {}
                line.startsWith("#") -> Text(line.trimStart('#').trim().uppercase(), Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") ->
                    Row { Text("•  ", fontFamily = Heading); Text(bold(line.trimStart().drop(2)), fontFamily = Heading, fontSize = 15.sp) }
                else -> Text(bold(line), fontFamily = Heading, fontSize = 15.sp)
            }
        }
    }
}

private fun bold(s: String): AnnotatedString = buildAnnotatedString {
    val parts = s.split("**")
    parts.forEachIndexed { i, p -> if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(p) } else append(p) }
}
