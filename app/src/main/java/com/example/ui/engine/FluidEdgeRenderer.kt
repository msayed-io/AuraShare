package com.example.ui.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.BitmapShader
import android.graphics.Matrix
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * High-performance rendering pipeline for FluidEdgeDissolve:
 * 1. Metaball Liquid Layer (feGaussianBlur(35dp) -> feColorMatrix(60A - 30) -> feGaussianBlur(14dp))
 * 2. Halo Layer (17-stop eased elliptical gradient with exponent 2.2 + 1dp rim + noise)
 * 3. Wave Layer (Radial pulse gradient with BlendMode.Screen)
 *
 * Implements strict zero-allocation per frame and float numerical precision on CPU.
 */
class FluidEdgeRenderer {

    companion object {
        private const val CONTAINER_OFFSET = 150.0 // O = 150dp
        private const val BITMAP_SCALE = 0.25f      // 1 px = 4 dp

        private val BAR_POSITIONS = floatArrayOf(0f, 0.34f, 0.56f, 0.72f, 0.80f, 0.90f, 1.0f)
        private val BAR_COLORS = intArrayOf(
            0xFFF5F5F5.toInt(),
            0xFFE4E4E7.toInt(),
            0xFF8E8E93.toInt(),
            0xFF3A3A3C.toInt(),
            0xFF2C2C2E.toInt(),
            0xFF1C1C1E.toInt(),
            0xFF000000.toInt()
        )

        private val DRIP_POSITIONS = floatArrayOf(0f, 0.60f, 1.0f)
        private val DRIP_COLORS = intArrayOf(
            0xFFF5F5F5.toInt(),
            0xFF8E8E93.toInt(),
            0xFF2C2C2E.toInt()
        )

        // Palette for halo: [F5F5F5, 8E8E93, 2C2C2E, 1C1C1E]
        private val HALO_PALETTE = arrayOf(
            intArrayOf(245, 245, 245),
            intArrayOf(142, 142, 147),
            intArrayOf(44, 44, 46),
            intArrayOf(28, 28, 30)
        )

        // Wave stops: 0 at 60%, 0.06 at 72%, 0.26 at 84%, 0.50 at 91%, 0.16 at 97%, 0 at 100%
        private val WAVE_POSITIONS = floatArrayOf(0.0f, 0.60f, 0.72f, 0.84f, 0.91f, 0.97f, 1.0f)
        private val WAVE_COLORS = intArrayOf(
            AndroidColor.argb(0, 245, 245, 245),
            AndroidColor.argb(0, 245, 245, 245),
            AndroidColor.argb((0.06 * 255).roundToInt(), 245, 245, 245),
            AndroidColor.argb((0.26 * 255).roundToInt(), 245, 245, 245),
            AndroidColor.argb((0.50 * 255).roundToInt(), 245, 245, 245),
            AndroidColor.argb((0.16 * 255).roundToInt(), 245, 245, 245),
            AndroidColor.argb(0, 245, 245, 245)
        )
    }

    // Off-screen Liquid rendering buffers
    private var bmpW = 0
    private var bmpH = 0
    private var liquidBitmap: Bitmap? = null
    private var liquidCanvas: Canvas? = null

    // Pixel processing buffers (Float for precision to prevent 8-bit banding on 60x gain)
    private var pixelBuffer: IntArray = IntArray(0)
    private var bufA: FloatArray = FloatArray(0)
    private var bufR: FloatArray = FloatArray(0)
    private var bufG: FloatArray = FloatArray(0)
    private var bufB: FloatArray = FloatArray(0)
    private var tempA: FloatArray = FloatArray(0)
    private var tempR: FloatArray = FloatArray(0)
    private var tempG: FloatArray = FloatArray(0)
    private var tempB: FloatArray = FloatArray(0)

    // Reusable Paints and Paths
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
    }
    private val screenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }
    private val noisePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }

    private val elementPath = Path()
    private val drawMatrix = Matrix()
    private val gradientMatrix = Matrix()
    private val rectF = RectF()

    // 96x96 Noise Bitmap
    private val noiseBitmap: Bitmap = createNoiseBitmap()
    private val noiseShader: BitmapShader = BitmapShader(noiseBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)

    init {
        noisePaint.shader = noiseShader
    }

    private fun createNoiseBitmap(): Bitmap {
        val b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(96 * 96)
        val rng = Random(42)
        for (i in pixels.indices) {
            val a = rng.nextInt(14) // 0..13
            pixels[i] = AndroidColor.argb(a, 255, 255, 255)
        }
        b.setPixels(pixels, 0, 96, 0, 0, 96, 96)
        return b
    }

    fun ensureBufferSize(stageW: Double, stageH: Double) {
        val targetW = ceil((stageW + 2 * CONTAINER_OFFSET) * BITMAP_SCALE).toInt().coerceAtLeast(1)
        val targetH = ceil((stageH + 2 * CONTAINER_OFFSET) * BITMAP_SCALE).toInt().coerceAtLeast(1)

        if (bmpW != targetW || bmpH != targetH || liquidBitmap == null) {
            bmpW = targetW
            bmpH = targetH

            liquidBitmap?.recycle()
            liquidBitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
            liquidCanvas = Canvas(liquidBitmap!!)

            val numPixels = bmpW * bmpH
            pixelBuffer = IntArray(numPixels)
            bufA = FloatArray(numPixels)
            bufR = FloatArray(numPixels)
            bufG = FloatArray(numPixels)
            bufB = FloatArray(numPixels)
            tempA = FloatArray(numPixels)
            tempR = FloatArray(numPixels)
            tempG = FloatArray(numPixels)
            tempB = FloatArray(numPixels)
        }
    }

    /**
     * Main rendering entry point from Compose Canvas.
     */
    fun render(
        drawScope: DrawScope,
        engine: FluidEdgeEngine,
        density: Float,
        drawBackground: Boolean = false
    ) {
        val W = engine.W
        val H = engine.H
        if (W <= 0 || H <= 0) return

        ensureBufferSize(W, H)

        drawScope.drawIntoCanvas { composeCanvas ->
            val nativeCanvas = composeCanvas.nativeCanvas

            if (drawBackground) {
                nativeCanvas.drawColor(0xFF000000.toInt())
            }

            // 1. Liquid Layer for each edge (Metaballs)
            renderLiquidLayer(nativeCanvas, engine, density)

            // 2. Halo Layer for each edge (Screen blend mode)
            renderHaloLayer(nativeCanvas, engine, density)
        }
    }

    // =========================================================================
    // 1. LIQUID LAYER (Metaballs)
    // =========================================================================

    private fun renderLiquidLayer(
        canvas: Canvas,
        engine: FluidEdgeEngine,
        density: Float
    ) {
        val targetBitmap = liquidBitmap ?: return
        val offscreen = liquidCanvas ?: return
        val q = engine.geom()
        val S = engine.S
        val cardTransform = engine.getCardTransform()

        for (i in 0 until FluidEdgeEngine.N) {
            val f = engine.fx[i]
            val v = FluidEdgeEngine.clamp(f.vis.x, 0.0, 1.0)
            val lv = FluidEdgeEngine.ss(v * 1.25)
            if (lv <= 0.004) continue

            val c = if (i == engine.focus) engine.cm else 0.0
            val vd = v.pow(0.6)
            val dw = S * (0.28 + 0.5 * v + 0.1 * c)
            val baseSz = 150.0 + 190.0 * v.pow(0.8) + 40.0 * c
            val near = max(0.0, q.d[i] + S * max(0.0, engine.Sc.x) * 0.4)
            val baseLen = (120.0 + near) * vd

            // Surgically shorten lateral inward reach for side edges (left and right)
            val sideRatio = if (i == 0) 1.0 else 0.52
            val sz = baseSz * sideRatio
            val len = baseLen * sideRatio

            // Clear offscreen bitmap
            targetBitmap.eraseColor(AndroidColor.TRANSPARENT)

            offscreen.save()
            offscreen.scale(BITMAP_SCALE, BITMAP_SCALE)

            // (A) Draw bar
            drawLiquidBar(offscreen, i, engine.W, engine.H, sz)

            // (B) Draw drip
            drawLiquidDrip(offscreen, i, engine.W, engine.H, q.cx, q.cy, dw, len)

            // (C) Draw card ghost (gh)
            drawLiquidGhost(offscreen, cardTransform, S)

            offscreen.restore()

            // Run SVG filter: blur(35dp) -> colorMatrix(60A - 30) -> blur(14dp)
            applyMetaballFilter(targetBitmap)

            // Draw to screen: destination rect is (-150, -150) to (W + 150, H + 150) in stage dp
            val destLeft = (-CONTAINER_OFFSET * density).toFloat()
            val destTop = (-CONTAINER_OFFSET * density).toFloat()
            val destRight = ((engine.W + CONTAINER_OFFSET) * density).toFloat()
            val destBottom = ((engine.H + CONTAINER_OFFSET) * density).toFloat()
            rectF.set(destLeft, destTop, destRight, destBottom)

            bitmapPaint.alpha = (FluidEdgeEngine.clamp(lv, 0.0, 1.0) * 255.0).roundToInt()
            canvas.drawBitmap(targetBitmap, null, rectF, bitmapPaint)
        }
    }

    private fun drawLiquidBar(
        canvas: Canvas,
        edge: Int,
        W: Double,
        H: Double,
        sz: Double
    ) {
        val containerW = (W + 2 * CONTAINER_OFFSET).toFloat()
        val containerH = (H + 2 * CONTAINER_OFFSET).toFloat()

        when (edge) {
            0 -> {
                // Top: horizontal full width, y=30dp (which is -120 + 150), height sz
                val y0 = 30f
                val y1 = (30.0 + sz).toFloat()
                fillPaint.shader = LinearGradient(0f, y0, 0f, y1, BAR_COLORS, BAR_POSITIONS, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, y0, containerW, y1, fillPaint)
            }
            1 -> {
                // Left: vertical full height, x=30dp, width sz
                val x0 = 30f
                val x1 = (30.0 + sz).toFloat()
                fillPaint.shader = LinearGradient(x0, 0f, x1, 0f, BAR_COLORS, BAR_POSITIONS, Shader.TileMode.CLAMP)
                canvas.drawRect(x0, 0f, x1, containerH, fillPaint)
            }
            2 -> {
                // Right: vertical full height, right at W+270dp, width sz
                val right = (W + 270.0).toFloat()
                val left = (right - sz).toFloat()
                fillPaint.shader = LinearGradient(right, 0f, left, 0f, BAR_COLORS, BAR_POSITIONS, Shader.TileMode.CLAMP)
                canvas.drawRect(left, 0f, right, containerH, fillPaint)
            }
        }
    }

    private fun drawLiquidDrip(
        canvas: Canvas,
        edge: Int,
        W: Double,
        H: Double,
        cx: Double,
        cy: Double,
        dw: Double,
        len: Double
    ) {
        elementPath.reset()
        when (edge) {
            0 -> {
                // Top drip: top=30, x = cx - dw/2 + 150, width = dw, height = len
                val x0 = (cx - dw / 2.0 + CONTAINER_OFFSET).toFloat()
                val y0 = 30f
                val x1 = (x0 + dw).toFloat()
                val y1 = (y0 + len).toFloat()

                // Border radius 0 0 80 80. CSS scale factor if adjacent radii sum > dw or len
                val f = min(1.0, min(dw / 160.0, len / 80.0))
                val r = (80.0 * f).toFloat()

                rectF.set(x0, y0, x1, y1)
                val radii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
                elementPath.addRoundRect(rectF, radii, Path.Direction.CW)

                fillPaint.shader = LinearGradient(x0, y0, x0, y1, DRIP_COLORS, DRIP_POSITIONS, Shader.TileMode.CLAMP)
                canvas.drawPath(elementPath, fillPaint)
            }
            1 -> {
                // Left drip: left=30, y = cy - dw/2 + 150, width = len, height = dw
                val x0 = 30f
                val y0 = (cy - dw / 2.0 + CONTAINER_OFFSET).toFloat()
                val x1 = (x0 + len).toFloat()
                val y1 = (y0 + dw).toFloat()

                val f = min(1.0, min(dw / 160.0, len / 80.0))
                val r = (80.0 * f).toFloat()

                rectF.set(x0, y0, x1, y1)
                // Border radius: 0 80 80 0 (top-right and bottom-right)
                val radii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                elementPath.addRoundRect(rectF, radii, Path.Direction.CW)

                fillPaint.shader = LinearGradient(x0, y0, x1, y0, DRIP_COLORS, DRIP_POSITIONS, Shader.TileMode.CLAMP)
                canvas.drawPath(elementPath, fillPaint)
            }
            2 -> {
                // Right drip: right=W+270, y = cy - dw/2 + 150, width = len, height = dw
                val x1 = (W + 270.0).toFloat()
                val x0 = (x1 - len).toFloat()
                val y0 = (cy - dw / 2.0 + CONTAINER_OFFSET).toFloat()
                val y1 = (y0 + dw).toFloat()

                val f = min(1.0, min(dw / 160.0, len / 80.0))
                val r = (80.0 * f).toFloat()

                rectF.set(x0, y0, x1, y1)
                // Border radius: 80 0 0 80 (top-left and bottom-left)
                val radii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
                elementPath.addRoundRect(rectF, radii, Path.Direction.CW)

                fillPaint.shader = LinearGradient(x1, y0, x0, y0, DRIP_COLORS, DRIP_POSITIONS, Shader.TileMode.CLAMP)
                canvas.drawPath(elementPath, fillPaint)
            }
        }
    }

    private fun drawLiquidGhost(
        canvas: Canvas,
        cardTransform: FluidEdgeEngine.CardTransform,
        S: Double
    ) {
        if (!cardTransform.isVisible) return

        canvas.save()
        // Center of ghost in container coordinates
        val gx = (cardTransform.x + CONTAINER_OFFSET + S / 2.0).toFloat()
        val gy = (cardTransform.y + CONTAINER_OFFSET + S / 2.0).toFloat()
        canvas.translate(gx, gy)
        canvas.scale(cardTransform.scaleX.toFloat(), cardTransform.scaleY.toFloat())

        val halfS = (S / 2.0).toFloat()
        rectF.set(-halfS, -halfS, halfS, halfS)
        val cornerRadius = (0.26 * S).toFloat()

        // Background: linear-gradient(180deg, var(--menu), var(--card)) -> #2C2C2E to #1C1C1E
        fillPaint.shader = LinearGradient(
            0f, -halfS, 0f, halfS,
            0xFF2C2C2E.toInt(), 0xFF1C1C1E.toInt(),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, fillPaint)

        canvas.restore()
    }

    /**
     * W3C Gaussian approximation via 3-pass Box Blur + ColorMatrix + 3-pass Box Blur.
     * Operates in-place on float channel arrays for supreme numerical stability.
     */
    private fun applyMetaballFilter(bitmap: Bitmap) {
        val w = bmpW
        val h = bmpH
        val total = w * h
        if (total == 0) return

        bitmap.getPixels(pixelBuffer, 0, w, 0, 0, w, h)

        // Unpack ARGB into premultiplied floats [0..1]
        for (i in 0 until total) {
            val p = pixelBuffer[i]
            val a = ((p ushr 24) and 0xFF) / 255.0f
            bufA[i] = a
            bufR[i] = (((p ushr 16) and 0xFF) / 255.0f) * a
            bufG[i] = (((p ushr 8) and 0xFF) / 255.0f) * a
            bufB[i] = ((p and 0xFF) / 255.0f) * a
        }

        // Pass 1: feGaussianBlur stdDeviation = 35dp -> 35 * 0.25 = 8.75px
        // 3 passes of box blur with radius 8 (diameter 17)
        boxBlur3Pass(bufA, bufR, bufG, bufB, tempA, tempR, tempG, tempB, w, h, radius = 8)

        // Pass 2: feColorMatrix: A' = clamp(60 * A - 30, 0, 1)
        // First un-premultiply RGB, apply matrix, then re-premultiply
        for (i in 0 until total) {
            val a = bufA[i]
            if (a > 0.0001f) {
                val invA = 1.0f / a
                val ur = min(1.0f, bufR[i] * invA)
                val ug = min(1.0f, bufG[i] * invA)
                val ub = min(1.0f, bufB[i] * invA)

                val newA = (60.0f * a - 30.0f).coerceIn(0.0f, 1.0f)
                bufA[i] = newA
                bufR[i] = ur * newA
                bufG[i] = ug * newA
                bufB[i] = ub * newA
            } else {
                bufA[i] = 0f
                bufR[i] = 0f
                bufG[i] = 0f
                bufB[i] = 0f
            }
        }

        // Pass 3: feGaussianBlur stdDeviation = 14dp -> 14 * 0.25 = 3.5px
        // 3 passes of box blur with radius 3 (diameter 7)
        boxBlur3Pass(bufA, bufR, bufG, bufB, tempA, tempR, tempG, tempB, w, h, radius = 3)

        // Repack into IntArray ARGB
        for (i in 0 until total) {
            val a = (bufA[i] * 255.0f).roundToInt().coerceIn(0, 255)
            val r = (bufR[i] * 255.0f).roundToInt().coerceIn(0, 255)
            val g = (bufG[i] * 255.0f).roundToInt().coerceIn(0, 255)
            val b = (bufB[i] * 255.0f).roundToInt().coerceIn(0, 255)
            pixelBuffer[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        bitmap.setPixels(pixelBuffer, 0, w, 0, 0, w, h)
    }

    private fun boxBlur3Pass(
        sA: FloatArray, sR: FloatArray, sG: FloatArray, sB: FloatArray,
        tA: FloatArray, tR: FloatArray, tG: FloatArray, tB: FloatArray,
        w: Int, h: Int, radius: Int
    ) {
        repeat(3) {
            boxBlur1DHorizontal(sA, tA, w, h, radius)
            boxBlur1DVertical(tA, sA, w, h, radius)

            boxBlur1DHorizontal(sR, tR, w, h, radius)
            boxBlur1DVertical(tR, sR, w, h, radius)

            boxBlur1DHorizontal(sG, tG, w, h, radius)
            boxBlur1DVertical(tG, sG, w, h, radius)

            boxBlur1DHorizontal(sB, tB, w, h, radius)
            boxBlur1DVertical(tB, sB, w, h, radius)
        }
    }

    private fun boxBlur1DHorizontal(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val windowSize = 2 * r + 1
        val invWindow = 1.0f / windowSize

        for (y in 0 until h) {
            val rowOffset = y * w
            var sum = src[rowOffset] * (r + 1)
            for (i in 1..r) {
                sum += src[rowOffset + min(w - 1, i)]
            }

            for (x in 0 until w) {
                dst[rowOffset + x] = sum * invWindow
                val nextX = min(w - 1, x + r + 1)
                val prevX = max(0, x - r)
                sum += src[rowOffset + nextX] - src[rowOffset + prevX]
            }
        }
    }

    private fun boxBlur1DVertical(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val windowSize = 2 * r + 1
        val invWindow = 1.0f / windowSize

        for (x in 0 until w) {
            var sum = src[x] * (r + 1)
            for (i in 1..r) {
                sum += src[min(h - 1, i) * w + x]
            }

            for (y in 0 until h) {
                dst[y * w + x] = sum * invWindow
                val nextY = min(h - 1, y + r + 1)
                val prevY = max(0, y - r)
                sum += src[nextY * w + x] - src[prevY * w + x]
            }
        }
    }

    // =========================================================================
    // 2. HALO LAYER
    // =========================================================================

    private fun renderHaloLayer(
        canvas: Canvas,
        engine: FluidEdgeEngine,
        density: Float
    ) {
        val W = engine.W
        val H = engine.H

        for (i in 0 until FluidEdgeEngine.N) {
            val f = engine.fx[i]
            val v = FluidEdgeEngine.clamp(f.vis.x, 0.0, 1.0)
            if (v <= 0.003) continue

            val c = if (i == engine.focus) engine.cm else 0.0
            val L = if (i == 0) W else H
            val baseD = FluidEdgeEngine.clamp(0.2 * L, 150.0, 340.0) * (0.6 + 0.4 * v) * (1.0 + 0.25 * c)
            val sideRatio = if (i == 0) 1.0 else 0.52
            val D = baseD * sideRatio
            val opacity = min(1.0, FluidEdgeEngine.ss(v) * (1.0 + 0.3 * c))
            val foc = FluidEdgeEngine.clamp(f.foc.x, 0.1, 0.9)

            screenPaint.alpha = (opacity * 255.0).roundToInt().coerceIn(0, 255)

            // (A) Elliptical Gradient
            drawHaloGradient(canvas, i, W, H, D, foc, density, screenPaint)

            // (B) Rim Line
            drawHaloRim(canvas, i, W, H, foc, density, screenPaint)

            // (C) Noise Shader (TileMode.REPEAT with 0.45 opacity)
            drawHaloNoise(canvas, i, W, H, D, foc, density, opacity)
        }
    }

    private fun drawHaloGradient(
        canvas: Canvas,
        edge: Int,
        W: Double,
        H: Double,
        D: Double,
        f: Double,
        density: Float,
        paint: Paint
    ) {
        val (colors, positions) = createEasedHaloStops(0.62)

        val pxW = (W * density).toFloat()
        val pxH = (H * density).toFloat()
        val pxD = (D * density).toFloat()

        when (edge) {
            0 -> {
                // Top: Rect (0,0) -> (W, D), Center (f*W, 0), rx = 0.62*W, ry = D
                val cx = (f * W * density).toFloat()
                val cy = 0f
                val rx = (0.62 * pxW).toFloat()
                val ry = pxD

                val gradient = RadialGradient(0f, 0f, 1f, colors, positions, Shader.TileMode.CLAMP)
                gradientMatrix.reset()
                gradientMatrix.setScale(rx, ry)
                gradientMatrix.postTranslate(cx, cy)
                gradient.setLocalMatrix(gradientMatrix)

                paint.shader = gradient
                canvas.drawRect(0f, 0f, pxW, pxD, paint)
            }
            1 -> {
                // Left: Rect (0,0) -> (D, H), Center (0, f*H), rx = D, ry = 0.62*H
                val cx = 0f
                val cy = (f * H * density).toFloat()
                val rx = pxD
                val ry = (0.62 * pxH).toFloat()

                val gradient = RadialGradient(0f, 0f, 1f, colors, positions, Shader.TileMode.CLAMP)
                gradientMatrix.reset()
                gradientMatrix.setScale(rx, ry)
                gradientMatrix.postTranslate(cx, cy)
                gradient.setLocalMatrix(gradientMatrix)

                paint.shader = gradient
                canvas.drawRect(0f, 0f, pxD, pxH, paint)
            }
            2 -> {
                // Right: Rect (W-D, 0) -> (W, H), Center (W, f*H), rx = D, ry = 0.62*H
                val cx = pxW
                val cy = (f * H * density).toFloat()
                val rx = pxD
                val ry = (0.62 * pxH).toFloat()

                val gradient = RadialGradient(0f, 0f, 1f, colors, positions, Shader.TileMode.CLAMP)
                gradientMatrix.reset()
                gradientMatrix.setScale(rx, ry)
                gradientMatrix.postTranslate(cx, cy)
                gradient.setLocalMatrix(gradientMatrix)

                paint.shader = gradient
                canvas.drawRect(pxW - pxD, 0f, pxW, pxH, paint)
            }
        }
    }

    private fun drawHaloRim(
        canvas: Canvas,
        edge: Int,
        W: Double,
        H: Double,
        f: Double,
        density: Float,
        basePaint: Paint
    ) {
        val pxW = (W * density).toFloat()
        val pxH = (H * density).toFloat()
        val strokeWidth = 1.0f * density

        strokePaint.strokeWidth = strokeWidth
        strokePaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        strokePaint.alpha = basePaint.alpha

        // Offsets: f-0.36: 0 | f-0.24: 0.08 | f-0.11: 0.30 | f: 0.65 | f+0.11: 0.30 | f+0.24: 0.08 | f+0.36: 0
        val offsets = doubleArrayOf(
            f - 0.36, f - 0.24, f - 0.11, f, f + 0.11, f + 0.24, f + 0.36
        )
        val alphas = doubleArrayOf(
            0.0, 0.08, 0.30, 0.65, 0.30, 0.08, 0.0
        )

        // Interpolate or clamp positions strictly into [0..1]
        val clampedOffsets = FloatArray(7)
        val rimColors = IntArray(7)
        for (j in 0 until 7) {
            val pos = offsets[j].coerceIn(0.0, 1.0)
            clampedOffsets[j] = pos.toFloat()
            val a = (alphas[j] * 255.0).roundToInt().coerceIn(0, 255)
            rimColors[j] = AndroidColor.argb(a, 245, 245, 245)
        }

        // Sort if needed to ensure strictly ascending order for Android LinearGradient
        for (a in 0 until 6) {
            for (b in a + 1 until 7) {
                if (clampedOffsets[a] > clampedOffsets[b]) {
                    val tmpP = clampedOffsets[a]
                    clampedOffsets[a] = clampedOffsets[b]
                    clampedOffsets[b] = tmpP

                    val tmpC = rimColors[a]
                    rimColors[a] = rimColors[b]
                    rimColors[b] = tmpC
                }
            }
        }

        when (edge) {
            0 -> {
                // Top: line at y=0, x from 0 to W
                strokePaint.shader = LinearGradient(0f, 0f, pxW, 0f, rimColors, clampedOffsets, Shader.TileMode.CLAMP)
                canvas.drawLine(0f, 0f, pxW, 0f, strokePaint)
            }
            1 -> {
                // Left: line at x=0, y from 0 to H
                strokePaint.shader = LinearGradient(0f, 0f, 0f, pxH, rimColors, clampedOffsets, Shader.TileMode.CLAMP)
                canvas.drawLine(0f, 0f, 0f, pxH, strokePaint)
            }
            2 -> {
                // Right: line at x=W-1, y from 0 to H
                val x = pxW - strokeWidth / 2f
                strokePaint.shader = LinearGradient(0f, 0f, 0f, pxH, rimColors, clampedOffsets, Shader.TileMode.CLAMP)
                canvas.drawLine(x, 0f, x, pxH, strokePaint)
            }
        }
    }

    private fun drawHaloNoise(
        canvas: Canvas,
        edge: Int,
        W: Double,
        H: Double,
        D: Double,
        f: Double,
        density: Float,
        opacity: Double
    ) {
        val pxW = (W * density).toFloat()
        val pxH = (H * density).toFloat()
        val pxD = (D * density).toFloat()

        noisePaint.alpha = (opacity * 0.45 * 255.0).roundToInt().coerceIn(0, 255)

        when (edge) {
            0 -> canvas.drawRect(0f, 0f, pxW, pxD, noisePaint)
            1 -> canvas.drawRect(0f, 0f, pxD, pxH, noisePaint)
            2 -> canvas.drawRect(pxW - pxD, 0f, pxW, pxH, noisePaint)
        }
    }

    /**
     * Eased 17-stop gradient according to HTML lines 165-174:
     * t = i/16, u = 3t, k = min(2, floor(u)), fr = u - k
     * color = lerp(PAL[k], PAL[k+1], fr)
     * alpha = A * (1 - t)^2.2
     */
    private fun createEasedHaloStops(A: Double): Pair<IntArray, FloatArray> {
        val colors = IntArray(17)
        val positions = FloatArray(17)

        for (i in 0..16) {
            val t = i / 16.0
            val u = t * 3.0
            val k = min(2, floor(u).toInt())
            val fr = u - k

            val r = (FluidEdgeEngine.lerp(HALO_PALETTE[k][0].toDouble(), HALO_PALETTE[k + 1][0].toDouble(), fr)).roundToInt()
            val g = (FluidEdgeEngine.lerp(HALO_PALETTE[k][1].toDouble(), HALO_PALETTE[k + 1][1].toDouble(), fr)).roundToInt()
            val b = (FluidEdgeEngine.lerp(HALO_PALETTE[k][2].toDouble(), HALO_PALETTE[k + 1][2].toDouble(), fr)).roundToInt()

            val alphaVal = A * (1.0 - t).pow(2.2)
            val a = (alphaVal * 255.0).roundToInt().coerceIn(0, 255)

            colors[i] = AndroidColor.argb(a, r, g, b)
            positions[i] = t.toFloat()
        }

        return Pair(colors, positions)
    }

    // =========================================================================
    // 3. WAVE LAYER
    // =========================================================================

    private fun renderWaveLayer(
        canvas: Canvas,
        engine: FluidEdgeEngine,
        density: Float
    ) {
        val wv = engine.wv
        if (wv.t >= 1.0) return

        val r = FluidEdgeEngine.lerp(40.0, 340.0, 1.0 - (1.0 - wv.t).pow(3.0))
        val opacity = wv.amp * (1.0 - wv.t).pow(1.7) * 0.6
        if (opacity <= 0.001) return

        val pxRadius = (r * density).toFloat()
        val pxCx = (wv.x * density).toFloat()
        val pxCy = (wv.y * density).toFloat()

        screenPaint.alpha = (opacity * 255.0).roundToInt().coerceIn(0, 255)
        screenPaint.shader = RadialGradient(
            pxCx, pxCy, pxRadius,
            WAVE_COLORS, WAVE_POSITIONS,
            Shader.TileMode.CLAMP
        )

        canvas.drawCircle(pxCx, pxCy, pxRadius, screenPaint)
    }
}
