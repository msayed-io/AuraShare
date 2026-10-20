package com.example.ui.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect as ComposeRenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.model.AuraMediaType
import com.example.data.model.MediaItem
import com.example.ui.theme.MatteBorder
import com.example.ui.theme.MatteSurfaceCard
import com.example.ui.theme.MatteSurfaceElevated
import com.example.ui.theme.PlatinumPrimary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@RequiresApi(Build.VERSION_CODES.S)
private object Api31RenderEffectHelper {
    fun createMorphRenderEffect(matrixValues: FloatArray): ComposeRenderEffect? {
        return try {
            val blur = android.graphics.RenderEffect.createBlurEffect(50f, 50f, android.graphics.Shader.TileMode.CLAMP)
            val colorFilter = android.graphics.ColorMatrixColorFilter(matrixValues)
            val effect = android.graphics.RenderEffect.createColorFilterEffect(colorFilter, blur)
            effect.asComposeRenderEffect()
        } catch (t: Throwable) {
            null
        }
    }
}

/**
 * High-performance fluid drag controller with 1:1 true screen coordinate mapping,
 * instantaneous micro-scale spring feedback, and terminal velocity injection.
 */
@Stable
class FluidDragController(
    val density: Density,
    val coroutineScope: CoroutineScope,
    val onSendItem: (MediaItem) -> Unit
) {
    var activeItem by mutableStateOf<MediaItem?>(null)
        private set

    // Direct zero-latency 60/120fps coordinate tracking
    var posX by mutableFloatStateOf(0f)
        private set
    var posY by mutableFloatStateOf(0f)
        private set

    val animScale = Animatable(1f)
    val animAlpha = Animatable(1f)
    val animSwell = Animatable(0f)
    val animReleaseY = Animatable(0f)

    var isAttracted by mutableStateOf(false)
        private set

    var isAbsorbed by mutableStateOf(false)
        private set

    fun startDrag(item: MediaItem, touchRootPos: Offset) {
        activeItem = item
        posX = touchRootPos.x
        posY = touchRootPos.y
        isAttracted = false
        isAbsorbed = false

        coroutineScope.launch {
            animAlpha.snapTo(1f)
            animScale.snapTo(1f)
            animSwell.snapTo(0f)
            animReleaseY.snapTo(touchRootPos.y)

            // Instantaneous micro-scale spring confirmation (1.1f) the millisecond long-press triggers
            animScale.animateTo(
                targetValue = 1.1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessHigh
                )
            )
        }
    }

    fun dragBy(delta: Offset) {
        if (activeItem == null || isAbsorbed) return

        // 1:1 physical pixel mapping without latency
        posX += delta.x
        posY += delta.y

        // Full top edge attraction zone (distance < 200dp from screen top)
        val attractionThreshold = with(density) { 200.dp.toPx() }
        val inZone = posY < attractionThreshold

        if (inZone != isAttracted) {
            isAttracted = inZone
        }

        if (inZone) {
            val pull = ((attractionThreshold - posY) / attractionThreshold).coerceIn(0f, 1f)
            coroutineScope.launch { animSwell.snapTo(pull) }
        } else {
            if (animSwell.value > 0f) {
                coroutineScope.launch { animSwell.animateTo(0f, tween(100)) }
            }
        }
    }

    fun endDrag(velocity: Velocity) {
        val item = activeItem ?: return
        if (isAbsorbed) return

        val attractionThreshold = with(density) { 200.dp.toPx() }
        val isSwipedUp = velocity.y < -300f
        val isInsideTopZone = posY < attractionThreshold

        if (isInsideTopZone || isSwipedUp) {
            isAbsorbed = true
            isAttracted = true

            coroutineScope.launch {
                animReleaseY.snapTo(posY)

                launch {
                    // Propels liquid asset completely past top edge using terminal velocity
                    animReleaseY.animateTo(
                        targetValue = -160f,
                        initialVelocity = velocity.y,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium
                        )
                    )
                }
                launch {
                    // Seamlessly scales down to 0f
                    animScale.animateTo(
                        targetValue = 0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium
                        )
                    )
                }
                launch {
                    // Fades alpha channel to 0f as liquid dissolves into edge
                    animAlpha.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(130, easing = FastOutSlowInEasing)
                    )
                }
                launch {
                    animSwell.animateTo(1.2f, tween(80))
                    animSwell.animateTo(0f, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium))
                }

                // Unlock background binary streaming sequence immediately upon layout animation completion
                onSendItem(item)
                activeItem = null
                isAttracted = false
                isAbsorbed = false
            }
        } else {
            // Dismiss and return smoothly
            coroutineScope.launch {
                launch {
                    animScale.animateTo(
                        targetValue = 0.8f,
                        animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium)
                    )
                }
                launch {
                    animAlpha.animateTo(0f, tween(140))
                }
                launch {
                    animSwell.animateTo(0f, tween(100))
                }
                activeItem = null
                isAttracted = false
            }
        }
    }

    fun cancelDrag() {
        if (activeItem == null || isAbsorbed) return
        coroutineScope.launch {
            animAlpha.animateTo(0f, tween(120))
            animSwell.animateTo(0f, tween(100))
            activeItem = null
            isAttracted = false
        }
    }
}

@Composable
fun rememberFluidDragController(
    onSendItem: (MediaItem) -> Unit
): FluidDragController {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    return remember(density, coroutineScope, onSendItem) {
        FluidDragController(density, coroutineScope, onSendItem)
    }
}

/**
 * Modifier to attach fluid drag gesture recognition to any media item.
 * Uses official Android detectDragGesturesAfterLongPress with velocity tracking.
 */
fun Modifier.fluidDraggable(
    item: MediaItem,
    getItemRootOffset: () -> Offset,
    dragController: FluidDragController
): Modifier = pointerInput(item) {
    val velocityTracker = VelocityTracker()
    detectDragGesturesAfterLongPress(
        onDragStart = { localOffset ->
            velocityTracker.resetTracking()
            val rootPos = getItemRootOffset() + localOffset
            dragController.startDrag(item, rootPos)
        },
        onDrag = { change, dragAmount ->
            change.consume()
            velocityTracker.addPosition(change.uptimeMillis, change.position)
            dragController.dragBy(dragAmount)
        },
        onDragEnd = {
            val velocity = velocityTracker.calculateVelocity()
            dragController.endDrag(velocity)
        },
        onDragCancel = {
            dragController.cancelDrag()
        }
    )
}

@Composable
fun FluidMetaballContainer(
    dragController: FluidDragController,
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val activeItem = dragController.activeItem

    // Alpha threshold matrix from reference doc
    val alphaThresholdMatrix = remember {
        ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1f, 0f, 0f,
                0f, 0f, 0f, 60f, -30f
            )
        )
    }

    val morphRenderEffect: ComposeRenderEffect? = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Api31RenderEffectHelper.createMorphRenderEffect(alphaThresholdMatrix.values)
        } else null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Base main application layout
        content()

        // Full Screen Edge-to-Edge Fluid Liquid Attraction Layer
        if (activeItem != null) {
            val swell = dragController.animSwell.value
            val currentX = dragController.posX
            val currentY = if (dragController.isAbsorbed) dragController.animReleaseY.value else dragController.posY

            // CRITICAL: Force LTR for the floating drag overlay to ensure 1:1 physical screen pixel mapping
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            if (dragController.isAttracted && morphRenderEffect != null) {
                                renderEffect = morphRenderEffect
                            }
                        }
                ) {
                    // Top Edge-to-Edge Fluid Meniscus and Metaball Bridge
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawEdgeLiquidAttraction(
                            dropCenter = Offset(currentX, currentY),
                            dropRadius = with(density) { 44.dp.toPx() } * dragController.animScale.value,
                            swellFactor = swell,
                            color = PlatinumPrimary.copy(alpha = 0.95f)
                        )
                    }

                    // Absolute Floating Draggable Card
                    val cardSize = 92.dp
                    val halfSizePx = with(density) { 46.dp.toPx() }

                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    (currentX - halfSizePx).roundToInt(),
                                    (currentY - halfSizePx).roundToInt()
                                )
                            }
                            .size(cardSize)
                            .scale(dragController.animScale.value)
                            .alpha(dragController.animAlpha.value)
                            .clip(RoundedCornerShape(26.dp))
                            .background(MatteSurfaceElevated)
                            .border(
                                width = if (dragController.isAttracted) 2.dp else 1.dp,
                                color = if (dragController.isAttracted) PlatinumPrimary else MatteBorder,
                                shape = RoundedCornerShape(26.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        when (activeItem.mediaType) {
                            AuraMediaType.IMAGE -> {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(activeItem.uri)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = activeItem.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(26.dp))
                                )
                            }
                            AuraMediaType.VIDEO -> {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Videocam,
                                        contentDescription = null,
                                        tint = PlatinumPrimary,
                                        modifier = Modifier.size(38.dp)
                                    )
                                }
                            }
                            AuraMediaType.FILE -> {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Description,
                                        contentDescription = null,
                                        tint = PlatinumPrimary,
                                        modifier = Modifier.size(38.dp)
                                    )
                                }
                            }
                        }

                        // Floating upward arrow pill indicator
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(PlatinumPrimary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowUpward,
                                contentDescription = null,
                                tint = MatteSurfaceElevated,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Draws the edge-to-edge liquid attraction layer and the organic metaball bridge
 * connecting the top edge of the device to the floating liquid droplet.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEdgeLiquidAttraction(
    dropCenter: Offset,
    dropRadius: Float,
    swellFactor: Float,
    color: Color
) {
    val screenWidth = size.width
    val baseEdgeThickness = 14f // Base edge lip at top of device

    if (swellFactor <= 0.02f) {
        // Minimal edge boundary
        drawRect(
            color = color.copy(alpha = 0.2f),
            topLeft = Offset.Zero,
            size = androidx.compose.ui.geometry.Size(screenWidth, baseEdgeThickness)
        )
        return
    }

    val swellWidth = (140f + swellFactor * 260f).coerceAtMost(screenWidth * 0.9f)
    val swellDepth = baseEdgeThickness + (swellFactor * 130f)
    val swellCenterX = dropCenter.x.coerceIn(swellWidth / 2f, screenWidth - swellWidth / 2f)

    val topPath = Path().apply {
        moveTo(0f, 0f)
        lineTo(screenWidth, 0f)
        lineTo(screenWidth, baseEdgeThickness)

        val rightSwellX = (swellCenterX + swellWidth / 2f).coerceAtMost(screenWidth)
        lineTo(rightSwellX, baseEdgeThickness)

        val leftSwellX = (swellCenterX - swellWidth / 2f).coerceAtLeast(0f)

        // Cubic bezier creating organic liquid droplet swell from the screen edge
        cubicTo(
            rightSwellX - (swellWidth * 0.25f), baseEdgeThickness,
            swellCenterX + (swellWidth * 0.2f), swellDepth,
            swellCenterX, swellDepth
        )
        cubicTo(
            swellCenterX - (swellWidth * 0.2f), swellDepth,
            leftSwellX + (swellWidth * 0.25f), baseEdgeThickness,
            leftSwellX, baseEdgeThickness
        )

        lineTo(0f, baseEdgeThickness)
        close()
    }

    drawPath(path = topPath, color = color)

    // Liquid Metaball Bridge connecting top swell directly into the drop
    val distanceToSwell = dropCenter.y - swellDepth
    if (distanceToSwell < (dropRadius * 2.8f) && distanceToSwell > -dropRadius) {
        val bridgeWidthSwell = (dropRadius * 1.4f * swellFactor).coerceAtLeast(20f)
        val bridgeWidthDrop = dropRadius * 0.9f

        val bridgePath = Path().apply {
            val pTopLeft = Offset(swellCenterX - bridgeWidthSwell, swellDepth * 0.85f)
            val pTopRight = Offset(swellCenterX + bridgeWidthSwell, swellDepth * 0.85f)
            val pBottomLeft = Offset(dropCenter.x - bridgeWidthDrop, dropCenter.y)
            val pBottomRight = Offset(dropCenter.x + bridgeWidthDrop, dropCenter.y)

            val midY = (swellDepth + dropCenter.y) / 2f
            val pinch = 0.55f

            moveTo(pTopLeft.x, pTopLeft.y)
            quadraticTo(
                pTopLeft.x + (pBottomLeft.x - pTopLeft.x) * pinch, midY,
                pBottomLeft.x, pBottomLeft.y
            )
            lineTo(pBottomRight.x, pBottomRight.y)
            quadraticTo(
                pTopRight.x + (pBottomRight.x - pTopRight.x) * pinch, midY,
                pTopRight.x, pTopRight.y
            )
            close()
        }
        drawPath(path = bridgePath, color = color)
    }
}
