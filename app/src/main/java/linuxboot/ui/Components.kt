package linuxboot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import linuxboot.R
import linuxboot.ui.theme.LocalExtraColors

enum class Tone { Neutral, Good, Warn, Bad, Accent }
enum class Btn { Primary, Tonal, Outlined, Danger, Text }

/** Status text from the app is stored in capitals; show it in sentence case. */
fun String.sentence(): String = lowercase().replaceFirstChar { it.uppercase() }

fun String.friendly(): String {
    val i = indexOf(':')
    val head = if (i >= 0) substring(0, i) else this
    if (head.any { it.isLowerCase() }) return this
    return head.sentence() + (if (i >= 0) substring(i) else "")
}

@Composable
fun toneColors(t: Tone): Pair<Color, Color> {
    val c = MaterialTheme.colorScheme
    val x = LocalExtraColors.current
    return when (t) {
        Tone.Neutral -> c.surfaceVariant to c.onSurfaceVariant
        Tone.Good -> x.successContainer to x.onSuccessContainer
        Tone.Warn -> x.warningContainer to x.onWarningContainer
        Tone.Bad -> c.errorContainer to c.onErrorContainer
        Tone.Accent -> c.primaryContainer to c.onPrimaryContainer
    }
}

@Composable
fun Ico(
    id: Int,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = LocalContentColor.current,
    description: String? = null,
) {
    Icon(painterResource(id), description, modifier.size(size), tint)
}

@Composable
fun Page(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
fun Panel(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = MaterialTheme.shapes.large
    var m = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(MaterialTheme.colorScheme.surface)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(m.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable
fun ScreenBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Ico(R.drawable.ic_arrow_back, description = "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
fun StatusChip(text: String, tone: Tone = Tone.Neutral, icon: Int? = null) {
    val (bg, fg) = toneColors(tone)
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Ico(icon, size = 14.dp, tint = fg)
        Text(text, color = fg, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

private fun distroColor(id: String): Color = when (id) {
    "alpine" -> Color(0xFF0D597F)
    "debian" -> Color(0xFFA81D33)
    "ubuntu" -> Color(0xFFE95420)
    "arch" -> Color(0xFF1793D1)
    "fedora" -> Color(0xFF3C6EB4)
    "void" -> Color(0xFF478061)
    else -> Color(0xFF5C6BC0)
}

/** Letter badge in the distribution's colour (no third-party logos are bundled). */
@Composable
fun DistroBadge(id: String, name: String, size: Dp = 44.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 3.2f)).background(distroColor(id)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.firstOrNull()?.uppercase() ?: "?",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.42f).sp,
        )
    }
}

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    enabled: Boolean = true,
    style: Btn = Btn.Primary,
) {
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Ico(icon, size = 18.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, maxLines = 1)
    }
    val shape = RoundedCornerShape(14.dp)
    val m = modifier.heightIn(min = 46.dp)
    when (style) {
        Btn.Primary -> Button(onClick = onClick, modifier = m, enabled = enabled, shape = shape, content = content)
        Btn.Tonal -> FilledTonalButton(onClick = onClick, modifier = m, enabled = enabled, shape = shape, content = content)
        Btn.Outlined -> OutlinedButton(onClick = onClick, modifier = m, enabled = enabled, shape = shape, content = content)
        Btn.Danger -> Button(
            onClick = onClick, modifier = m, enabled = enabled, shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            content = content,
        )
        Btn.Text -> TextButton(onClick = onClick, modifier = m, enabled = enabled, shape = shape, content = content)
    }
}

@Composable
fun ActionRow(
    icon: Int,
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val tint = when {
        !enabled -> c.onSurface.copy(alpha = 0.38f)
        danger -> c.error
        else -> c.onSurface
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (danger) c.errorContainer else c.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Ico(icon, tint = tint, size = 20.dp) }
        Column(Modifier.weight(1f)) {
            Text(title, color = tint, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
        Ico(R.drawable.ic_chevron_right, tint = c.onSurfaceVariant, size = 18.dp)
    }
}

@Composable
fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

@Composable
fun Banner(tone: Tone, text: String, modifier: Modifier = Modifier) {
    val (bg, fg) = toneColors(tone)
    val icon = when (tone) {
        Tone.Bad -> R.drawable.ic_error
        Tone.Warn -> R.drawable.ic_warning
        Tone.Good -> R.drawable.ic_check
        else -> R.drawable.ic_info
    }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Ico(icon, tint = fg, size = 20.dp)
        Text(text, color = fg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** Terminal-style output; scrolls to the newest line. */
@Composable
fun LogPanel(lines: List<String>, modifier: Modifier = Modifier, maxHeight: Dp = 220.dp) {
    val scroll = rememberScrollState()
    LaunchedEffect(lines.size) { scroll.scrollTo(scroll.maxValue) }
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0B0E13))
            .verticalScroll(scroll)
            .padding(12.dp),
    ) {
        Text(
            lines.joinToString("\n"),
            color = Color(0xFFC9D1D9),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
    }
}
