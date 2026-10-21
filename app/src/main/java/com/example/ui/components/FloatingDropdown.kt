package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** Density-aware placement below the actual trigger, never a hard-coded pixel offset. */
private class CapsuleMenuPosition(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = if (layoutDirection == LayoutDirection.Rtl) anchorBounds.left
            else anchorBounds.right - popupContentSize.width
        return IntOffset(
            x.coerceIn(gap, (windowSize.width - popupContentSize.width - gap).coerceAtLeast(gap)),
            (anchorBounds.bottom + gap).coerceAtMost((windowSize.height - popupContentSize.height - gap).coerceAtLeast(gap))
        )
    }
}

@Composable
fun FloatingDropdown(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    minWidth: Dp = 178.dp,
    maxHeight: Dp = 360.dp,
    alignment: Alignment = Alignment.TopStart,
    content: @Composable () -> Unit
) {
    val transition = remember { MutableTransitionState(false) }
    LaunchedEffect(expanded) { transition.targetState = expanded }
    if (!transition.currentState && !transition.targetState && transition.isIdle) return
    val density = LocalDensity.current
    val gap = with(density) { 8.dp.roundToPx() }
    val lift = with(density) { 12.dp.roundToPx() }
    val position = remember(gap) { CapsuleMenuPosition(gap) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val origin = TransformOrigin(if (rtl) 0f else 1f, 0f)
    val shape = RoundedCornerShape(20.dp)
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val maxAvailableHeight = (LocalConfiguration.current.screenHeightDp.dp - 80.dp).coerceAtLeast(80.dp)
    val easing = CubicBezierEasing(.25f, 1f, .5f, 1f)
    Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = expanded, dismissOnBackPress = true, dismissOnClickOutside = true)
    ) {
        AnimatedVisibility(
            visibleState = transition,
            enter = fadeIn(tween(500, easing = easing)) + slideInVertically(tween(500, easing = easing)) { -lift } +
                scaleIn(tween(500, easing = easing), initialScale = .95f, transformOrigin = origin),
            exit = fadeOut(tween(500, easing = easing)) + slideOutVertically(tween(500, easing = easing)) { -lift } +
                scaleOut(tween(500, easing = easing), targetScale = .95f, transformOrigin = origin)
        ) {
            Column(
                modifier = modifier.widthIn(min = minWidth).width(IntrinsicSize.Max)
                    .shadow(if (dark) 18.dp else 10.dp, shape, clip = false)
                    .shadow(3.dp, shape, clip = false)
                    .clip(shape)
                    .background(if (dark) Color(0xFF2C2C2E) else Color.White)
                    .border(.5.dp, if (dark) Color.White.copy(alpha = .12f) else Color(0xFF121A1B).copy(alpha = .12f), shape)
                    .then(if (!expanded) Modifier.clearAndSetSemantics {} else Modifier)
                    .heightIn(max = minOf(maxHeight, maxAvailableHeight))
                    .verticalScroll(rememberScrollState()).padding(4.dp)
            ) { content() }
        }
    }
}
