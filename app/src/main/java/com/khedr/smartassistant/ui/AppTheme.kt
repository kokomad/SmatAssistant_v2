package com.khedr.smartassistant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import kotlin.math.hypot

/**
 * Three visual themes inspired by layered / peeled-paper wallpapers:
 * emerald layers over black, turquoise page-curl over black, gold page-curl over sapphire blue.
 */
enum class AppTheme(
    val id: Int,
    val title: String,
    val accent: Color,
    val accent2: Color,
    val onAccent: Color
) {
    EMERALD(0, "زمردي", Color(0xFF4DE38A), Color(0xFF1F8F47), Color(0xFF04210F)),
    TURQUOISE(1, "فيروزي", Color(0xFF22E5FF), Color(0xFF2AC9B5), Color(0xFF00212A)),
    GOLD(2, "ذهبي", Color(0xFFFFC21A), Color(0xFF3346FF), Color(0xFF2A1A00));

    fun colorScheme(): ColorScheme = darkColorScheme(
        primary = accent,
        onPrimary = onAccent,
        secondary = accent2,
        onSecondary = Color.White,
        background = Color.Black,
        onBackground = Color.White,
        surface = Color(0xFF0D1214),
        onSurface = Color.White,
        surfaceVariant = Color(0xFF1A2226),
        onSurfaceVariant = Color(0xFFCFD8DC),
        outline = accent.copy(alpha = 0.60f),
        outlineVariant = accent.copy(alpha = 0.30f)
    )

    companion object {
        fun fromId(id: Int): AppTheme = entries.firstOrNull { it.id == id } ?: EMERALD
    }
}

/** Full-screen artwork for the selected theme. Also reused (scaled) for the theme picker swatches. */
@Composable
fun ThemedBackground(theme: AppTheme, modifier: Modifier = Modifier.fillMaxSize()) {
    Canvas(modifier) { drawThemeArt(theme) }
}

fun DrawScope.drawThemeArt(theme: AppTheme) {
    when (theme) {
        AppTheme.EMERALD -> drawEmerald()
        AppTheme.TURQUOISE -> drawTurquoise()
        AppTheme.GOLD -> drawGold()
    }
}

// ───────────────────────── helpers ─────────────────────────

private fun DrawScope.poly(brush: Brush, a: Offset, b: Offset, c: Offset, d: Offset? = null) {
    val path = Path().apply {
        moveTo(a.x, a.y)
        lineTo(b.x, b.y)
        lineTo(c.x, c.y)
        if (d != null) lineTo(d.x, d.y)
        close()
    }
    drawPath(path, brush)
}

/** Soft shadow band under the straight edge a→b (fades out over [depth] pixels). */
private fun DrawScope.shade(a: Offset, b: Offset, depth: Float, color: Color) {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len = hypot(dx, dy)
    if (len == 0f) return
    val nx = -dy / len
    val ny = dx / len
    val a2 = Offset(a.x + nx * depth, a.y + ny * depth)
    val b2 = Offset(b.x + nx * depth, b.y + ny * depth)
    val mid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
    val brush = Brush.linearGradient(
        colors = listOf(color, Color.Transparent),
        start = mid,
        end = Offset(mid.x + nx * depth, mid.y + ny * depth)
    )
    poly(brush, a, b, b2, a2)
}

/** Fake blurred shadow: several translucent strokes of growing width. */
private fun DrawScope.softStrokeShadow(path: Path, color: Color, maxWidth: Float, layers: Int, alphaPerLayer: Float) {
    for (i in 1..layers) {
        val width = maxWidth * i / layers
        drawPath(
            path = path,
            color = color.copy(alpha = alphaPerLayer),
            style = Stroke(width = width, join = StrokeJoin.Round)
        )
    }
}

// ───────────────────────── emerald: green layers over black, curved-edge glow ─────────────────────────

private fun DrawScope.drawEmerald() {
    val w = size.width
    val h = size.height
    fun p(x: Float, y: Float) = Offset(x * w, y * h)

    drawRect(Color.Black)

    // bright top slab
    poly(
        Brush.verticalGradient(listOf(Color(0xFF33B862), Color(0xFF29A455)), 0f, h * 0.45f),
        p(0f, 0f), p(1f, 0f), p(1f, 0.385f), p(0f, 0.175f)
    )
    // layer A
    poly(SolidColorBrushCompat(Color(0xFF209B4A)), p(0f, 0.175f), p(1f, 0.385f), p(1f, 0.53f), p(0f, 0.215f))
    shade(p(0f, 0.175f), p(1f, 0.385f), h * 0.035f, Color(0x66000000))
    // layer B (darkest, sits on the black diagonal)
    poly(SolidColorBrushCompat(Color(0xFF1B7B35)), p(0f, 0.215f), p(1f, 0.53f), p(1f, 0.67f), p(0f, 0.235f))
    shade(p(0f, 0.215f), p(1f, 0.53f), h * 0.03f, Color(0x66000000))
    // glossy hairlines on the layer edges
    drawLine(Color(0x33FFFFFF), p(0f, 0.175f), p(1f, 0.385f), strokeWidth = 2f)
    drawLine(Color(0x22FFFFFF), p(0f, 0.215f), p(1f, 0.53f), strokeWidth = 2f)

    // curved-screen edge glow on both sides
    val glow = listOf(Color(0x00FFFFFF), Color(0x66FFFFFF), Color(0x00FFFFFF))
    val gw = w * 0.11f
    drawRect(Brush.horizontalGradient(glow, 0f, gw), topLeft = Offset(0f, 0f), size = androidx.compose.ui.geometry.Size(gw, h))
    drawRect(Brush.horizontalGradient(glow, w - gw, w), topLeft = Offset(w - gw, 0f), size = androidx.compose.ui.geometry.Size(gw, h))
}

// ───────────────────────── turquoise: cyan page-curl over black ─────────────────────────

private fun DrawScope.drawTurquoise() {
    val w = size.width
    val h = size.height
    fun p(x: Float, y: Float) = Offset(x * w, y * h)

    drawRect(Color.Black)

    // silhouette of the page (everything right/below the curl line)
    val page = Path().apply {
        moveTo(w, 0.105f * h)
        cubicTo(0.95f * w, 0.19f * h, 0.87f * w, 0.20f * h, 0.80f * w, 0.222f * h)
        lineTo(0f, 0.83f * h)
        lineTo(0f, h)
        lineTo(w, h)
        close()
    }
    drawPath(
        page,
        Brush.verticalGradient(
            listOf(Color(0xFF22D3B4), Color(0xFF10D8E6), Color(0xFF00DFFF)),
            0.10f * h, h
        )
    )

    // the folded flap
    val flap = Path().apply {
        moveTo(0f, 0.83f * h)
        lineTo(0.80f * w, 0.222f * h)
        cubicTo(0.74f * w, 0.30f * h, 0.77f * w, 0.50f * h, 0.68f * w, 0.62f * h)
        cubicTo(0.60f * w, 0.71f * h, 0.25f * w, 0.71f * h, 0f, 0.83f * h)
        close()
    }
    clipPath(page) {
        softStrokeShadow(flap, Color(0xFF0A7F86), w * 0.16f, 8, 0.055f)
    }
    drawPath(
        flap,
        Brush.linearGradient(
            listOf(Color(0xFFB5EBFF), Color(0xFF6FE0FF), Color(0xFF36E0C8)),
            start = p(0.30f, 0.50f), end = p(0.62f, 0.70f)
        )
    )
    // bright fold highlight
    drawLine(Color(0x55FFFFFF), p(0f, 0.83f), p(0.80f, 0.222f), strokeWidth = 3f)
}

// ───────────────────────── gold: yellow page-curl over sapphire blue ─────────────────────────

private fun DrawScope.drawGold() {
    val w = size.width
    val h = size.height
    fun p(x: Float, y: Float) = Offset(x * w, y * h)

    drawRect(Color(0xFF050620))

    // sapphire blue wedge (top-right)
    poly(
        Brush.linearGradient(
            listOf(Color(0xFF1021FF), Color(0xFF0A19F2), Color(0xFF0510B5)),
            start = p(0f, 0f), end = p(1f, 0.80f)
        ),
        p(0f, 0.07f), p(0f, 0f), p(1f, 0f), p(1f, 0.795f)
    )

    // golden page (bottom-left)
    val page = Path().apply {
        moveTo(0f, 0.07f * h)
        lineTo(w, 0.795f * h)
        lineTo(w, h)
        lineTo(0f, h)
        close()
    }
    drawPath(
        page,
        Brush.linearGradient(
            listOf(Color(0xFFFFB800), Color(0xFFFFB400), Color(0xFFC77A0A)),
            start = p(0f, 0.30f), end = p(1f, 1f)
        )
    )

    // peeled flap
    val flap = Path().apply {
        moveTo(0.23f * w, 0.237f * h)
        lineTo(w, 0.795f * h)
        cubicTo(0.93f * w, 0.71f * h, 0.80f * w, 0.68f * h, 0.62f * w, 0.665f * h)
        cubicTo(0.42f * w, 0.645f * h, 0.26f * w, 0.58f * h, 0.24f * w, 0.42f * h)
        cubicTo(0.235f * w, 0.34f * h, 0.235f * w, 0.28f * h, 0.23f * w, 0.237f * h)
        close()
    }
    clipPath(page) {
        softStrokeShadow(flap, Color(0xFF8A3600), w * 0.24f, 9, 0.07f)
    }
    drawPath(
        flap,
        Brush.linearGradient(
            listOf(Color(0xFFFFE27A), Color(0xFFFFC21A), Color(0xFFFFB000)),
            start = p(0.60f, 0.505f), end = p(0.28f, 0.65f)
        )
    )
    drawLine(Color(0x66FFFFFF), p(0.23f, 0.237f), p(1f, 0.795f), strokeWidth = 3f)

    // tiny dark sliver at the top-left tip of the fold
    poly(
        androidx.compose.ui.graphics.SolidColor(Color(0xCC03041A)),
        p(0f, 0.07f), p(0.115f, 0.155f), p(0f, 0.097f)
    )
}

@Suppress("FunctionName")
private fun SolidColorBrushCompat(c: Color): Brush = androidx.compose.ui.graphics.SolidColor(c)
