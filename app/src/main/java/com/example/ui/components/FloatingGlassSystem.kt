package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight

private val PressEasing = CubicBezierEasing(.25f, 1f, .5f, 1f)

data class FloatingBackdropState(val layer: GraphicsLayer, val rootOrigin: State<Offset>)
val LocalFloatingBackdrop = staticCompositionLocalOf<FloatingBackdropState?> { null }

/** Record the actual scrolling content once. Overlay lenses replay pixels, never UI or semantics. */
@Composable
fun FloatingBackdropHost(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
    overlay: @Composable BoxScope.() -> Unit
) {
    val layer = rememberGraphicsLayer()
    val origin = remember { mutableStateOf(Offset.Zero) }
    val backdrop = remember(layer) { FloatingBackdropState(layer, origin) }
    Box(modifier.fillMaxSize().onGloballyPositioned { origin.value = it.positionInWindow() }) {
        Box(Modifier.fillMaxSize().drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        }) { content() }
        CompositionLocalProvider(LocalFloatingBackdrop provides backdrop) { overlay() }
    }
}

/** Uses the Android 12+ render pipeline; older supported OS versions retain translucent glass. */
@Composable
private fun BackdropLens(modifier: Modifier, radius: Dp) {
    val backdrop = LocalFloatingBackdrop.current ?: return
    var position by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.onGloballyPositioned { position = it.positionInWindow() }
        .blur(radius, edgeTreatment = BlurredEdgeTreatment.Unbounded)
        .drawBehind {
            val delta = backdrop.rootOrigin.value - position
            translate(delta.x, delta.y) { drawLayer(backdrop.layer) }
        })
}

@Composable
fun FloatingGlassSurface(
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    shape: Shape = RoundedCornerShape(22.dp),
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = remember { Animatable(1f) }
    LaunchedEffect(pressed) {
        scale.animateTo(if (pressed) .96f else 1f, tween(if (pressed) 80 else 240, easing = PressEasing))
    }
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val tint = if (dark) Color(0xFF1C1C1E) else Color(0xFFF4F1E4)
    val border = if (dark) Color.White.copy(alpha = .08f) else Color(0xFF121A1B).copy(alpha = .12f)
    Box(
        modifier.height(height).graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .shadow(12.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = .4f), spotColor = Color.Black.copy(alpha = .4f))
            .shadow(2.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = .6f), spotColor = Color.Black.copy(alpha = .6f))
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick) else Modifier)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        BackdropLens(Modifier.matchParentSize(), 24.dp)
        Box(Modifier.matchParentSize().background(tint).border(BorderStroke(.5.dp, border), shape))
        content()
    }
}

/** Two independent drawing-only overlays: a masked 20dp live blur and the ambient vignette. */
@Composable
fun FloatingTopDissolve(modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val color = if (dark) Color.Black else Color(0xFFEAE6D2)
    val mask = Brush.verticalGradient(0f to Color.Black, .45f to Color.Black.copy(alpha = .75f), 1f to Color.Transparent)
    val vignette = Brush.verticalGradient(0f to color.copy(alpha = if (dark) .92f else .95f), .45f to color.copy(alpha = .45f), 1f to Color.Transparent)
    Box(modifier.fillMaxWidth().height(125.dp)) {
        Box(Modifier.matchParentSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent { drawContent(); drawRect(mask, blendMode = BlendMode.DstIn) }) {
            BackdropLens(Modifier.matchParentSize(), 20.dp)
        }
        Box(Modifier.matchParentSize().background(vignette))
    }
}

@Composable
fun CircularBackButton(onClick: () -> Unit, modifier: Modifier = Modifier, contentDescription: String = "العودة") {
    FloatingGlassSurface(modifier.size(44.dp), shape = CircleShape, contentDescription = contentDescription, onClick = onClick) {
        Icon(if (LocalLayoutDirection.current == LayoutDirection.Rtl) Icons.Default.ChevronRight else Icons.Default.ChevronLeft,
            contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
    }
}

private data class NavEntry(val key: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val tag: String)
private val NavEntries = listOf(
    NavEntry("images", "الصور", Icons.Default.Image, "tab_images_button"),
    NavEntry("videos", "الفيديو", Icons.Default.Videocam, "tab_videos_button"),
    NavEntry("files", "الملفات", Icons.Default.Description, "tab_files_button")
)

@Composable
fun FloatingBottomNavigation(selectedTab: String, onSelectTab: (String) -> Unit, modifier: Modifier = Modifier) {
    val positions = remember { mutableStateMapOf<String, Pair<Float, Float>>() }
    val density = LocalDensity.current
    val selected = positions[selectedTab]
    val x by animateDpAsState(with(density) { (selected?.first ?: 0f).toDp() }, spring(dampingRatio = .85f, stiffness = 600f), label = "activeTabX")
    val width by animateDpAsState(with(density) { (selected?.second ?: 0f).toDp() }, spring(dampingRatio = .85f, stiffness = 600f), label = "activeTabWidth")
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    FloatingGlassSurface(modifier = modifier, height = 48.dp, shape = RoundedCornerShape(24.dp)) {
        Box(Modifier.padding(6.dp).selectableGroup()) {
            if (selected != null) Box(Modifier.align(AbsoluteAlignment.TopLeft).absoluteOffset(x = x).width(width).height(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (dark) Color(0xFF2C2C2E) else Color(0xFFA7AA63))
                .border(.5.dp, if (dark) Color.White.copy(alpha = .12f) else Color(0xFFA7AA63).copy(alpha = .35f), RoundedCornerShape(18.dp)))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                NavEntries.forEach { item ->
                    val active = item.key == selectedTab
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    val scale = remember { Animatable(1f) }
                    LaunchedEffect(pressed) { scale.animateTo(if (pressed) .96f else 1f, tween(if (pressed) 80 else 240, easing = PressEasing)) }
                    Row(Modifier.height(36.dp)
                        .animateContentSize(spring(dampingRatio = .85f, stiffness = 600f))
                        .onGloballyPositioned { positions[item.key] = it.positionInParent().x to it.size.width.toFloat() }
                        .graphicsLayer { scaleX = scale.value; scaleY = scale.value; alpha = if (active) 1f else .6f }
                        .clip(RoundedCornerShape(18.dp))
                        .selectable(selected = active, interactionSource = interaction, indication = null, role = Role.Tab, onClick = { onSelectTab(item.key) })
                        .semantics { contentDescription = item.label }
                        .testTag(item.tag).padding(horizontal = if (active) 16.dp else 14.dp),
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(item.icon, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
                        AnimatedVisibility(visible = active,
                            enter = fadeIn(tween(240)) + expandHorizontally(tween(240)),
                            exit = fadeOut(tween(120)) + shrinkHorizontally(tween(240))) {
                            Text(item.label, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp,
                                fontWeight = FontWeight.Bold, softWrap = false, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}
