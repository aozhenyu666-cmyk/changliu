package com.changliu.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 长流统一视觉：宣纸底 + 墨字 + 陶土橙。
 *
 * 一手是暖纸、总控是米纸+朱印、驾驶员是陶土橙，这里取三者的交集：
 * 纸色背景、衬线大标题、细描边卡片，不用阴影和大色块。
 */
object ChangliuColors {
    val Paper = Color(0xFFF7F3EA)
    val PaperHi = Color(0xFFFFFDF7)
    val Ink = Color(0xFF1F1C17)
    val InkSoft = Color(0xFF4A453C)
    val Muted = Color(0xFF6F6A60)
    val Line = Color(0xFFE6DFD1)
    val Clay = Color(0xFFC0553A)
    val ClaySoft = Color(0xFFF5E4DC)
    val ClayDeep = Color(0xFF8E3C28)
    val Indigo = Color(0xFF41597A)
    val IndigoSoft = Color(0xFFE5EAF2)
    val Moss = Color(0xFF4C6B50)
    val MossSoft = Color(0xFFE3EBE2)
    val Amber = Color(0xFFA9762A)
    val AmberSoft = Color(0xFFF4E8D2)
    val Seal = Color(0xFF9E3B2E)
    val SealSoft = Color(0xFFF3DEDA)

    // 深色：墨底、纸字，橙色略提亮
    val Night = Color(0xFF14130F)
    val NightHi = Color(0xFF1E1C17)
    val NightLine = Color(0xFF332F27)
    val NightInk = Color(0xFFEDE7DA)
    val NightMuted = Color(0xFFA39C8D)
    val NightClay = Color(0xFFD97B5E)
    val NightClaySoft = Color(0xFF3A2620)
    val NightIndigo = Color(0xFF9DB4D4)
    val NightIndigoSoft = Color(0xFF232C3A)
    val NightMoss = Color(0xFF9DBFA3)
    val NightMossSoft = Color(0xFF223026)
    val NightSeal = Color(0xFFD98878)
    val NightSealSoft = Color(0xFF3A2220)
}

private val LightScheme = lightColorScheme(
    primary = ChangliuColors.Clay,
    onPrimary = Color.White,
    primaryContainer = ChangliuColors.ClaySoft,
    onPrimaryContainer = ChangliuColors.ClayDeep,
    secondary = ChangliuColors.Indigo,
    onSecondary = Color.White,
    secondaryContainer = ChangliuColors.IndigoSoft,
    onSecondaryContainer = ChangliuColors.Indigo,
    tertiary = ChangliuColors.Seal,
    onTertiary = Color.White,
    background = ChangliuColors.Paper,
    onBackground = ChangliuColors.Ink,
    surface = ChangliuColors.PaperHi,
    onSurface = ChangliuColors.Ink,
    surfaceVariant = ChangliuColors.Paper,
    onSurfaceVariant = ChangliuColors.Muted,
    outline = ChangliuColors.Line,
    outlineVariant = ChangliuColors.Line,
    error = ChangliuColors.Seal,
    onError = Color.White,
    errorContainer = ChangliuColors.SealSoft,
    onErrorContainer = ChangliuColors.Seal,
)

private val DarkScheme = darkColorScheme(
    primary = ChangliuColors.NightClay,
    onPrimary = ChangliuColors.Night,
    primaryContainer = ChangliuColors.NightClaySoft,
    onPrimaryContainer = ChangliuColors.NightClay,
    secondary = ChangliuColors.NightIndigo,
    onSecondary = ChangliuColors.Night,
    secondaryContainer = ChangliuColors.NightIndigoSoft,
    onSecondaryContainer = ChangliuColors.NightIndigo,
    tertiary = ChangliuColors.NightSeal,
    onTertiary = ChangliuColors.Night,
    background = ChangliuColors.Night,
    onBackground = ChangliuColors.NightInk,
    surface = ChangliuColors.NightHi,
    onSurface = ChangliuColors.NightInk,
    surfaceVariant = ChangliuColors.Night,
    onSurfaceVariant = ChangliuColors.NightMuted,
    outline = ChangliuColors.NightLine,
    outlineVariant = ChangliuColors.NightLine,
    error = ChangliuColors.NightSeal,
    onError = ChangliuColors.Night,
    errorContainer = ChangliuColors.NightSealSoft,
    onErrorContainer = ChangliuColors.NightSeal,
)

private val ChangliuTypography = Typography(
    // 大标题用衬线，像书页上的题目；正文保持无衬线，密度稳定
    displaySmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 34.sp,
        lineHeight = 42.sp,
        letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 26.sp,
        lineHeight = 34.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 21.sp,
        lineHeight = 29.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.5.sp,
        lineHeight = 23.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 10.5.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.2.sp,
    ),
)

@Composable
fun ChangliuTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = ChangliuTypography,
        content = content,
    )
}
