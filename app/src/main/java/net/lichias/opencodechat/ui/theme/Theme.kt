package net.lichias.opencodechat.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    MaterialTheme(colorScheme = dynamicDarkColorScheme(context), content = content)
}

/**
 * Soft, colorful radial "aurora" blobs over a near-black base,
 * in the spirit of the Gemini app backdrop.
 */
@Composable
fun GradientBackdrop(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Canvas(modifier) {
        drawRect(Color(0xFF0D0D14))
        fun blob(color: Color, cx: Float, cy: Float, radius: Float, alpha: Float) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = radius,
                )
            )
        }
        val dim = minOf(size.width, size.height)
        blob(cs.primary, size.width * 0.10f, size.height * 0.02f, dim * 1.1f, 0.22f)
        blob(cs.tertiary, size.width * 1.05f, size.height * 0.22f, dim * 1.2f, 0.16f)
        blob(cs.secondary, size.width * 0.30f, size.height * 1.02f, dim * 1.1f, 0.14f)
        blob(cs.primaryContainer, size.width * 1.00f, size.height * 0.88f, dim * 0.9f, 0.10f)
    }
}
