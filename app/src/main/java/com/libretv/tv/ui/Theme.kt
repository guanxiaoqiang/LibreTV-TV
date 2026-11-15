package com.libretv.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object TvColors {
    val Background = Color(0xFF0E1116)
    val Surface = Color(0xFF161B22)
    val SurfaceHigh = Color(0xFF1F2630)
    val Primary = Color(0xFFED93B1)
    val PrimaryDim = Color(0xFF993556)
    val OnSurface = Color(0xFFE6EDF3)
    val OnSurfaceMuted = Color(0xFF8B949E)
    val FocusRing = Color(0xFFED93B1)
    val FocusHalo = Color(0x55ED93B1)
    val Danger = Color(0xFFE24B4A)
    val Success = Color(0xFF639922)
    val Warning = Color(0xFFE8A33D)
    val Info = Color(0xFF5AA9E6)
}

val TvBackgroundBrush = Brush.verticalGradient(
    listOf(Color(0xFF0E1116), Color(0xFF12161D), Color(0xFF0E1116))
)

/**
 * The standard TV focus treatment: a double ring plus a bright halo.
 *
 * Scaling deliberately lives on the parent [FocusableTile], not here. A scale
 * applied after `Modifier.clip` would be clipped back to the unscaled bounds by
 * the card's own rounded corners, which is exactly why the old focus state was
 * barely visible on a TV. Here we only draw attention to the card.
 */
@Composable
fun Modifier.tvFocusable(
    focused: Boolean,
    shape: RoundedCornerShape = RoundedCornerShape(10.dp)
): Modifier = this
    .border(
        width = if (focused) 7.dp else 0.dp,
        color = if (focused) TvColors.FocusHalo else Color.Transparent,
        shape = shape
    )
    .border(
        width = if (focused) 3.dp else 0.dp,
        color = if (focused) TvColors.FocusRing else Color.Transparent,
        shape = shape
    )

@Composable
fun FocusableSurface(
    focused: Boolean,
    shape: RoundedCornerShape = RoundedCornerShape(10.dp),
    color: Color = TvColors.Surface,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .background(color, shape)
            .tvFocusable(focused, shape)
            .padding(10.dp)
    ) {
        content()
    }
}
