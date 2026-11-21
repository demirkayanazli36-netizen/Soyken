package com.example.ui.tv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.SoykenGold

fun isAndroidTvDevice(context: Context): Boolean {
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    val isTvMode = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    val hasLeanback = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    val hasNoTouch = !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    return isTvMode || hasLeanback || hasNoTouch
}

@Composable
fun rememberIsAndroidTv(): Boolean {
    val context = LocalContext.current
    return remember(context) { isAndroidTvDevice(context) }
}

/**
 * Adds Android TV D-Pad focusability with Soyken Play's gold (#FFB800) focus ring and subtle scale.
 * Invisible when unfocused so mobile touch aesthetics remain completely unchanged.
 */
fun Modifier.soykenTvFocus(
    shape: Shape,
    focusBorderColor: Color = SoykenGold,
    focusBorderWidth: Dp = 2.5.dp,
    scaleOnFocus: Float = 1.03f,
    addFocusable: Boolean = true
): Modifier = composed {
    var isFocused by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) scaleOnFocus else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "tv_focus_scale"
    )
    val ringColor by animateColorAsState(
        targetValue = if (isFocused) focusBorderColor else Color.Transparent,
        animationSpec = tween(durationMillis = 120),
        label = "tv_focus_ring"
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .border(
            width = if (isFocused) focusBorderWidth else 0.dp,
            color = ringColor,
            shape = shape
        )
        .onFocusChanged { isFocused = it.isFocused }
        .then(if (addFocusable) Modifier.focusable() else Modifier)
}
