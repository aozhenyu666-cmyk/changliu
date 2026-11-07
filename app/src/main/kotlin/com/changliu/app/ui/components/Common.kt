package com.changliu.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.changliu.app.ui.theme.ChangliuColors

enum class Tone { Neutral, Accent, Ok, Warn, Bad, Seal }

@Composable
fun isNight(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.4f

@Composable
fun Tone.fg(): Color = when (this) {
    Tone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    Tone.Accent -> MaterialTheme.colorScheme.primary
    Tone.Ok -> if (isNight()) ChangliuColors.NightMoss else ChangliuColors.Moss
    Tone.Warn -> if (isNight()) Color(0xFFE0B76A) else ChangliuColors.Amber
    Tone.Bad -> MaterialTheme.colorScheme.error
    Tone.Seal -> if (isNight()) ChangliuColors.NightSeal else ChangliuColors.Seal
}

@Composable
fun Tone.bg(): Color = when (this) {
    Tone.Neutral -> MaterialTheme.colorScheme.surfaceVariant
    Tone.Accent -> MaterialTheme.colorScheme.primaryContainer
    Tone.Ok -> if (isNight()) ChangliuColors.NightMossSoft else ChangliuColors.MossSoft
    Tone.Warn -> if (isNight()) Color(0xFF3A3020) else ChangliuColors.AmberSoft
    Tone.Bad -> MaterialTheme.colorScheme.errorContainer
    Tone.Seal -> if (isNight()) ChangliuColors.NightSealSoft else ChangliuColors.SealSoft
}

@Composable
fun StatusPill(text: String, tone: Tone = Tone.Neutral, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = tone.bg(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = tone.fg(),
                modifier = Modifier.width(6.dp).height(6.dp),
            ) {}
            Text(text, style = MaterialTheme.typography.labelSmall, color = tone.fg())
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    hint: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 26.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (hint != null) {
            Spacer(Modifier.width(8.dp))
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun ChangliuCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    tone: Tone = Tone.Neutral,
    content: @Composable ColumnScope.() -> Unit,
) {
    val base = if (onClick != null) modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable { onClick() } else modifier.fillMaxWidth()
    Surface(
        modifier = base,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
fun StatTile(
    label: String,
    value: String,
    hint: String,
    tone: Tone = Tone.Neutral,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Serif),
                color = tone.fg(),
            )
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun EntryRow(
    title: String,
    subtitle: String,
    value: String? = null,
    onClick: () -> Unit,
    showDivider: Boolean = false,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (value != null) {
                Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.outline)
        }
        if (showDivider) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
fun ThinProgress(progress: Float, tone: Tone = Tone.Accent, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().height(4.dp),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row {
            if (progress > 0f) {
                Surface(
                    modifier = Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp),
                    shape = RoundedCornerShape(50),
                    color = tone.fg(),
                ) {}
            }
        }
    }
}

/** 大号衬线数字，用于倒计时/额度。 */
@Composable
fun BigFigure(text: String, color: Color = MaterialTheme.colorScheme.onBackground) {
    Text(
        text,
        style = MaterialTheme.typography.displaySmall,
        color = color,
    )
}

@Composable
fun Kicker(text: String, tone: Tone = Tone.Neutral) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp),
        color = tone.fg(),
    )
}

@Composable
fun ScreenTitle(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
