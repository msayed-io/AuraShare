package com.example.ui.components

import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.model.AuraMediaType
import com.example.data.model.MediaItem
import com.example.ui.engine.FluidEdgeEngine
import com.example.ui.engine.FluidEdgeRenderer
import com.example.ui.theme.MatteBorder
import com.example.ui.theme.MatteSurfaceElevated
import com.example.ui.theme.PlatinumPrimary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

// Preserve FallbackBridgeGeometry for existing unit tests
internal data class FallbackBridgeGeometry(
    val anchorX: Float,
    val anchorY: Float,
    val cardX: Float,
    val cardY: Float,
    val anchorHalfWidth: Float,
    val cardHalfWidth: Float
)

internal fun fallbackBridgeGeometry(
    cardCenterX: Float,
    cardCenterY: Float,
    cardRadius: Float,
    maxDistance: Float = 150f,
    anchorCenterX: Float = cardCenterX
): FallbackBridgeGeometry? {
    if (!cardCenterX.isFinite() || !cardCenterY.isFinite() ||
        !cardRadius.isFinite() || !anchorCenterX.isFinite() || !maxDistance.isFinite() || maxDistance <= 0f || cardRadius <= 0f
    ) return null

    val dx = cardCenterX - anchorCenterX
    val dy = cardCenterY.coerceAtLeast(0f)
    val distance = kotlin.math.sqrt(dx * dx + dy * dy)
    if (distance >= maxDistance) return null

    val attraction = (1f - distance / maxDistance).coerceIn(0f, 1f)
    return FallbackBridgeGeometry(
        anchorX = anchorCenterX,
        anchorY = 0f,
        cardX = cardCenterX,
        cardY = cardCenterY,
        anchorHalfWidth = cardRadius * (.065f + .35f * attraction),
        cardHalfWidth = (cardRadius * (0.42f + 0.22f * attraction)).coerceIn(1f, cardRadius)
    )
}

/**
 * Controller connecting gesture inputs directly to FluidEdgeEngine.
 */
@Stable
class FluidDragController(
    val density: Density,
    val coroutineScope: CoroutineScope,
    val onSendItem: (MediaItem) -> Unit
) {
    var activeItem by mutableStateOf<MediaItem?>(null)
        internal set

    var engine: FluidEdgeEngine? = null
        internal set

    var touchX: Float = 0f
    var touchY: Float = 0f

    val isAttracted: Boolean
        get() = engine?.commit == true

    fun startDrag(item: MediaItem, touchRootPos: Offset) {
        activeItem = item
        touchX = touchRootPos.x
        touchY = touchRootPos.y
        val eng = engine ?: return
        val px = (touchX / density.density).toDouble()
        val py = (touchY / density.density).toDouble()
        val cardX = px - eng.S / 2.0
        val cardY = py - eng.S / 2.0
        eng.setHome(cardX, cardY)
        eng.X.set(cardX)
        eng.Y.set(cardY)
        eng.onDown(px, py, pointerId = null, nowMs = System.currentTimeMillis())
    }

    fun dragBy(delta: Offset) {
        val eng = engine ?: return
        if (eng.mode != FluidEdgeEngine.Mode.DRAG) return
        touchX += delta.x
        touchY += delta.y
        val px = (touchX / density.density).toDouble()
        val py = (touchY / density.density).toDouble()
        eng.onMove(px, py, System.currentTimeMillis())
    }

    fun endDrag() {
        val eng = engine ?: return
        eng.onUp(System.currentTimeMillis())
    }

    fun cancelDrag() {
        val eng = engine ?: return
        eng.onUp(System.currentTimeMillis())
    }
}

/**
 * Modifier to attach fluid drag gesture recognition to any media item.
 * Zero-velocity tracking library dependency: kinematics are driven directly by FluidEdgeEngine.
 */
fun Modifier.fluidDraggable(
    item: MediaItem,
    getItemRootOffset: () -> Offset,
    dragController: FluidDragController
): Modifier = pointerInput(item) {
    detectDragGesturesAfterLongPress(
        onDragStart = { localOffset ->
            val rootPos = getItemRootOffset() + localOffset
            dragController.startDrag(item, rootPos)
        },
        onDrag = { change, dragAmount ->
            change.consume()
            dragController.dragBy(dragAmount)
        },
        onDragEnd = {
            dragController.endDrag()
        },
        onDragCancel = {
            dragController.cancelDrag()
        }
    )
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

@Composable
fun FluidMetaballContainer(
    dragController: FluidDragController,
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Reduced motion detection
    val isReducedMotion = remember {
        try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            ) == 0f
        } catch (e: Exception) {
            false
        }
    }

    var hasSentCurrentCycle by remember { mutableStateOf(false) }

    val engine = remember {
        FluidEdgeEngine(
            isReducedMotion = isReducedMotion,
            onBuzz = {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            },
            onAbsorb = { edgeName ->
                if (!hasSentCurrentCycle) {
                    hasSentCurrentCycle = true
                    val item = dragController.activeItem
                    if (item != null) {
                        coroutineScope.launch(Dispatchers.IO) {
                            dragController.onSendItem(item)
                        }
                    }
                }
            },
            onGone = {
                // Keep item visible in background state until respawn completes
            },
            onRespawn = {
                hasSentCurrentCycle = false
                dragController.activeItem = null
            },
            onReturnedHome = {
                dragController.activeItem = null
            }
        )
    }

    dragController.engine = engine
    val renderer = remember { FluidEdgeRenderer() }

    // Frame invalidation ticker without causing recomposition of heavy content
    var frameTicker by remember { mutableIntStateOf(0) }

    // Frame loop with withFrameNanos (exact translation of HTML frame loop)
    LaunchedEffect(engine) {
        var lastNanos = 0L
        while (isActive) {
            withFrameNanos { nowNanos ->
                val dt = if (lastNanos == 0L) {
                    0.016
                } else {
                    min(0.033, (nowNanos - lastNanos) / 1e9)
                }
                lastNanos = nowNanos

                val nowMs = System.currentTimeMillis()
                engine.step(dt, nowMs)
                engine.sense(dt, nowMs)

                frameTicker++
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                val stageW = size.width / density.density
                val stageH = size.height / density.density
                engine.resize(stageW.toDouble(), stageH.toDouble())
            }
            .pointerInput(engine) {
                // Section 6: Stage pointerInput without touch slop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val px = (down.position.x / density.density).toDouble()
                    val py = (down.position.y / density.density).toDouble()

                    if (dragController.activeItem != null &&
                        engine.isPointInsideCard(px, py) &&
                        engine.mode != FluidEdgeEngine.Mode.ABS &&
                        engine.mode != FluidEdgeEngine.Mode.GONE
                    ) {
                        down.consume()
                        val pid = down.id.value.toInt()
                        dragController.touchX = down.position.x
                        dragController.touchY = down.position.y
                        engine.onDown(px, py, pointerId = pid, nowMs = System.currentTimeMillis())

                        while (true) {
                            val event = awaitPointerEvent()
                            val currentPointer = event.changes.firstOrNull { it.id == down.id } ?: break

                            if (!currentPointer.pressed) {
                                currentPointer.consume()
                                engine.onUp(System.currentTimeMillis())
                                break
                            }

                            currentPointer.consume()
                            dragController.touchX = currentPointer.position.x
                            dragController.touchY = currentPointer.position.y
                            val mx = (currentPointer.position.x / density.density).toDouble()
                            val my = (currentPointer.position.y / density.density).toDouble()
                            engine.onMove(mx, my, System.currentTimeMillis())
                        }
                    }
                }
            }
    ) {
        // Base main application layout
        content()

        // FluidEdgeDissolve Canvas Layer (Screen-blended Metaball liquid, 17-stop Halo, Wave)
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Read frameTicker to observe frame invalidation inside draw phase
            val _tick = frameTicker
            renderer.render(
                drawScope = this,
                engine = engine,
                density = density.density,
                drawBackground = false
            )
        }

        // Active Floating Draggable Card (transformed according to Section 5.5)
        val activeItem = dragController.activeItem
        if (activeItem != null) {
            val cardSizeDp = engine.S.dp

            // Full-screen LTR container guarantees (0,0) is physical top-left of device screen
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(
                        modifier = Modifier
                            .size(cardSizeDp)
                            .graphicsLayer {
                            // Read frameTicker to track 60/120fps transforms inside graphicsLayer without recomposition
                            val _tick = frameTicker
                            val transform = engine.getCardTransform()
                            if (!transform.isVisible) {
                                alpha = 0f
                            } else {
                                translationX = (transform.x * density.density).toFloat()
                                translationY = (transform.y * density.density).toFloat()
                                scaleX = transform.scaleX.toFloat()
                                scaleY = transform.scaleY.toFloat()
                                alpha = transform.opacity.toFloat()
                            }
                        }
                        .clip(RoundedCornerShape((engine.S * 0.26).dp))
                        .background(MatteSurfaceElevated)
                        .border(
                            width = if (engine.commit) 2.dp else 1.dp,
                            color = if (engine.commit) PlatinumPrimary else MatteBorder,
                            shape = RoundedCornerShape((engine.S * 0.26).dp)
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
                                    .clip(RoundedCornerShape((engine.S * 0.26).dp))
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
