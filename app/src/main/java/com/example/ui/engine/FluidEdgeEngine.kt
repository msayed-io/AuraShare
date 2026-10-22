package com.example.ui.engine

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Literal Kotlin translation of createFluidEdge from fluid-edge-dissolve-final.html.
 * Pure Kotlin class with zero Android UI dependencies, using Double precision throughout.
 * Strictly adheres to all equations, thresholds, and execution order from the HTML reference.
 */
class FluidEdgeEngine(
    val config: Config = Config(),
    var isReducedMotion: Boolean = false,
    val onBuzz: (() -> Unit)? = null,
    val onAbsorb: ((edgeName: String) -> Unit)? = null,
    val onGone: (() -> Unit)? = null,
    val onRespawn: (() -> Unit)? = null,
    val onReturnedHome: (() -> Unit)? = null
) {
    data class Config(
        val zone: Double = 190.0,
        val commitRatio: Double = 0.44,
        val uncommitRatio: Double = 0.58,
        val look: Double = 0.12,
        val project: Double = 0.499,
        val flickSpeed: Double = 500.0,
        val respawnDelay: Long = 1400L,
        val O: Double = 150.0
    )

    enum class Mode {
        DRAG, RETURN, ABS, GONE
    }

    data class EdgeDef(
        val name: String,
        val cls: String,
        val n: DoubleArray,
        val ang: String
    )

    companion object {
        val EDGES = arrayOf(
            EdgeDef(name = "top", cls = "t", n = doubleArrayOf(0.0, -1.0), ang = "90deg"),
            EdgeDef(name = "left", cls = "l", n = doubleArrayOf(-1.0, 0.0), ang = "180deg"),
            EdgeDef(name = "right", cls = "r", n = doubleArrayOf(1.0, 0.0), ang = "180deg")
        )
        const val N = 3
        const val O = 150.0

        // Tools as defined in HTML lines 124-128
        fun clamp(v: Double, a: Double = 0.0, b: Double = 1.0): Double =
            if (v < a) a else if (v > b) b else v

        fun lin(a: Double, b: Double, x: Double): Double =
            clamp((x - a) / (b - a), 0.0, 1.0)

        fun ss(x: Double): Double {
            val cx = clamp(x, 0.0, 1.0)
            return cx * cx * (3.0 - 2.0 * cx)
        }

        fun lerp(a: Double, b: Double, t: Double): Double =
            a + (b - a) * t

        fun rubber(v: Double, lo: Double, hi: Double): Double =
            if (v < lo) lo + (v - lo) * 0.3 else if (v > hi) hi + (v - hi) * 0.3 else v
    }

    /**
     * Apple-style spring: (response in seconds, dampingRatio) -> k=(2pi/r)^2, c=4pi*z/r.
     * Integrated using semi-implicit Euler in steps of <= 8ms.
     * v is updated first, then x uses the new v.
     */
    class Spring(
        var x: Double = 0.0,
        var r: Double = 0.5,
        var z: Double = 1.0
    ) {
        var v: Double = 0.0
        var t: Double = x

        fun set(target: Double) {
            x = target
            t = target
            v = 0.0
        }

        fun step(dt: Double) {
            val w = 2.0 * Math.PI / r
            val k = w * w
            val c = 4.0 * Math.PI * z / r
            var n = ceil(dt / 0.008).toInt()
            if (n < 1) n = 1
            val h = dt / n
            while (n-- > 0) {
                v += (k * (t - x) - c * v) * h
                x += v * h
            }
        }
    }

    class EdgeState(val def: EdgeDef) {
        val vis = Spring(x = 0.0, r = 0.34, z = 1.0)
        val foc = Spring(x = 0.5, r = 0.3, z = 1.0)
    }

    data class Zone(val R: Double, val C: Double, val U: Double)
    data class DragSample(val t: Long, val x: Double, val y: Double)
    data class WaveState(var t: Double = 1.0, var x: Double = 0.0, var y: Double = 0.0, var amp: Double = 1.0)

    data class GeomResult(
        val d: DoubleArray,
        val p: DoubleArray,
        val cx: Double,
        val cy: Double
    )

    // Springs
    val X = Spring(x = 0.0, r = 0.5, z = 1.0)
    val Y = Spring(x = 0.0, r = 0.5, z = 1.0)
    val Sc = Spring(x = 1.0, r = 0.5, z = 1.0)
    val Lf = Spring(x = 0.0, r = 0.3, z = 0.9)

    val fx = Array(N) { i -> EdgeState(EDGES[i]) }
    val wv = WaveState()

    var W: Double = 0.0
        private set
    var H: Double = 0.0
        private set
    var S: Double = 140.0
        private set
    var hx: Double = 0.0
        private set
    var hy: Double = 0.0
        private set
    var zone: Array<Zone> = Array(N) { Zone(190.0, 190.0 * 0.44, 190.0 * 0.58) }
        private set

    var mode: Mode = Mode.RETURN
        private set
    var edge: Int = 0
        private set
    var active: Int = -1
        private set
    var focus: Int = 0
        private set
    var commit: Boolean = false
        private set
    var cm: Double = 0.0
        private set
    var pulsed: Boolean = false
        private set

    private var respawnScheduledAt: Long = 0L
    private var hasScheduledRespawn: Boolean = false
    var returnStartTime: Long = 0L

    var dragId: Int? = null
        private set
    var dragOx: Double = 0.0
        private set
    var dragOy: Double = 0.0
        private set
    val dragSamples = ArrayList<DragSample>()

    fun setHome(homeX: Double, homeY: Double) {
        hx = homeX
        hy = homeY
    }

    fun resize(stageW: Double, stageH: Double, customCardSize: Double? = null, customHx: Double? = null, customHy: Double? = null) {
        W = stageW
        H = stageH
        S = customCardSize ?: clamp(min(W, H) * 0.34, 104.0, 160.0).roundToInt().toDouble()
        hx = customHx ?: ((W - S) / 2.0)
        hy = customHy ?: ((H - S) / 2.0)

        val hs = doubleArrayOf(hy, hx, hx)
        zone = Array(N) { i ->
            val h = hs[i]
            val R = min(config.zone, h * 0.78)
            Zone(R = R, C = R * config.commitRatio, U = R * config.uncommitRatio)
        }
    }

    fun geom(): GeomResult {
        val h = S * max(0.0, Sc.x) / 2.0
        val cx = X.x + S / 2.0
        val cy = Y.x + S / 2.0
        val d = doubleArrayOf(
            cy - h,
            cx - h,
            W - (cx + h)
        )
        val p = DoubleArray(N) { i ->
            clamp(1.0 - d[i] / zone[i].R, 0.0, 1.0)
        }
        return GeomResult(d = d, p = p, cx = cx, cy = cy)
    }

    fun vel(nowMs: Long = System.currentTimeMillis()): DoubleArray {
        val a = dragSamples
        val n = a.size
        if (n < 2) return doubleArrayOf(0.0, 0.0)
        val f = a.first()
        val l = a.last()
        val dt = (l.t - f.t) / 1000.0
        if (dt < 0.008 || (nowMs - l.t) > 90) return doubleArrayOf(0.0, 0.0)
        return doubleArrayOf((l.x - f.x) / dt, (l.y - f.y) / dt)
    }

    fun sense(dt: Double, nowMs: Long = System.currentTimeMillis()) {
        val q = geom()
        val t = doubleArrayOf(0.0, 0.0, 0.0)
        if (mode == Mode.DRAG) {
            val v = vel(nowMs)
            val wx = (X.x - hx) + config.look * v[0]
            val wy = (Y.x - hy) + config.look * v[1]
            val mag = hypot(wx, wy)
            val dir = DoubleArray(N)
            val sc = DoubleArray(N)
            var best = -1
            var bs = 0.0
            for (i in 0 until N) {
                val n = EDGES[i].n
                dir[i] = if (mag < 24.0) 0.0 else lin(0.5, 0.88, (wx * n[0] + wy * n[1]) / mag)
                sc[i] = q.p[i] * dir[i]
                if (sc[i] > bs) {
                    bs = sc[i]
                    best = i
                }
            }
            var a = active
            if (a >= 0 && sc[a] > 0.01) {
                if (best != a && sc[best] > sc[a] * 1.4) a = best
            } else {
                a = if (bs > 0.02) best else -1
            }
            active = a
            if (a >= 0) {
                focus = a
                t[a] = ss(q.p[a]) * dir[a]
                if (!commit && q.d[a] < zone[a].C && dir[a] > 0.6) {
                    commit = true
                    onBuzz?.invoke()
                    ripple(a, 0.8)
                } else if (commit && (q.d[a] > zone[a].U || dir[a] < 0.4)) {
                    commit = false
                }
            } else {
                commit = false
            }
        } else if (mode == Mode.ABS) {
            t[edge] = 1.0
            focus = edge
        } else {
            active = -1
            commit = false
        }

        cm += (((if (mode == Mode.ABS || commit) 1.0 else 0.0) - cm) * (1.0 - exp(-dt * 10.0)))
        for (i in 0 until N) {
            val f = fx[i]
            f.vis.t = t[i]
            f.vis.r = if (t[i] > f.vis.x) 0.34 else if (mode == Mode.GONE) 0.75 else 0.6
            f.vis.step(dt)
            if (f.vis.x < 0.0) {
                f.vis.x = 0.0
                f.vis.v = 0.0
            }
            if (t[i] == 0.0 && f.vis.x < 0.0015) {
                f.vis.x = 0.0
                f.vis.v = 0.0
            }
            if (i == active || (mode == Mode.ABS && i == edge)) {
                f.foc.t = clamp(if (i == 0) q.cx / W else q.cy / H, 0.1, 0.9)
            }
            f.foc.step(dt)
        }
    }

    fun ripple(e: Int, amp: Double) {
        if (isReducedMotion) return
        val q = geom()
        wv.t = 0.0
        wv.amp = amp
        wv.x = if (e == 0) q.cx else if (e == 1) 0.0 else W
        wv.y = if (e == 0) 0.0 else q.cy
    }

    fun step(dt: Double, nowMs: Long = System.currentTimeMillis()) {
        Lf.step(dt)
        if (mode == Mode.GONE) {
            if (hasScheduledRespawn && nowMs >= respawnScheduledAt) {
                hasScheduledRespawn = false
                reset()
            }
            return
        }
        if (mode == Mode.DRAG) {
            Sc.t = 1.0
            Sc.step(dt)
            return
        }
        if (mode == Mode.RETURN) {
            X.t = hx
            Y.t = hy
            Sc.t = 1.0
            X.step(dt)
            Y.step(dt)
            Sc.step(dt)

            if (returnStartTime > 0L) {
                val dist = hypot(X.x - hx, Y.x - hy)
                val speed = hypot(X.v, Y.v)
                val elapsed = nowMs - returnStartTime
                if ((dist < 6.0 && speed < 80.0) || elapsed > 350L) {
                    returnStartTime = 0L
                    onReturnedHome?.invoke()
                }
            }
            return
        }

        // Mode.ABS
        if (edge == 0) {
            X.v *= exp(-4.0 * dt)
            X.x += X.v * dt
            Y.step(dt)
        } else {
            Y.v *= exp(-4.0 * dt)
            Y.x += Y.v * dt
            X.step(dt)
        }

        val dn = when (edge) {
            0 -> Y.x
            1 -> X.x
            else -> W - X.x - S
        }
        Sc.t = if (dn < 90.0) 0.0 else 1.0
        Sc.step(dt)
        if (!pulsed && Sc.x < 0.45) {
            pulsed = true
            ripple(edge, 0.9)
        }
        if (Sc.x < 0.02 && Sc.t == 0.0) {
            gone(nowMs)
        }
    }

    fun gone(nowMs: Long = System.currentTimeMillis()) {
        mode = Mode.GONE
        Sc.set(0.0)
        onAbsorb?.invoke(EDGES[edge].name)
        onGone?.invoke()
        respawnScheduledAt = nowMs + config.respawnDelay
        hasScheduledRespawn = true
    }

    fun reset() {
        hasScheduledRespawn = false
        dragId = null
        active = -1
        commit = false
        cm = 0.0
        pulsed = false
        returnStartTime = 0L
        wv.t = 1.0
        for (f in fx) {
            f.vis.set(0.0)
            f.foc.set(0.5)
        }
        X.r = 0.5
        Y.r = 0.5
        X.z = 1.0
        Y.z = 1.0
        Sc.r = 0.5
        Sc.z = 1.0
        X.set(hx)
        Y.set(hy)
        Sc.set(0.0)
        Sc.t = 1.0
        Lf.set(0.0)
        mode = Mode.RETURN
        onRespawn?.invoke()
    }

    fun absorb(e: Int, v: DoubleArray) {
        if (!commit) ripple(e, 0.7)
        mode = Mode.ABS
        edge = e
        focus = e
        commit = true
        pulsed = false
        if (e == 0) {
            Y.t = -S * 0.6
        } else {
            X.t = if (e == 1) -S * 0.6 else W - S * 0.4
        }
        X.r = 0.42
        Y.r = 0.42
        X.z = 1.0
        Y.z = 1.0
        X.v = v[0]
        Y.v = v[1]
        Sc.r = 0.42
        Sc.z = 1.0
        Sc.t = 1.0
    }

    fun giveBack(v: DoubleArray, nowMs: Long = System.currentTimeMillis()) {
        mode = Mode.RETURN
        returnStartTime = nowMs
        commit = false
        val sp = hypot(v[0], v[1])
        X.r = 0.5
        Y.r = 0.5
        val z = lerp(1.0, 0.82, ss(lin(250.0, 1600.0, sp)))
        X.z = z
        Y.z = z
        X.v = v[0]
        Y.v = v[1]
        Sc.r = 0.45
        Sc.z = 1.0
    }

    fun onDown(px: Double, py: Double, pointerId: Int? = null, nowMs: Long = System.currentTimeMillis()) {
        if (mode == Mode.ABS || mode == Mode.GONE) return
        returnStartTime = 0L
        dragId = pointerId
        dragOx = px - X.x
        dragOy = py - Y.x
        dragSamples.clear()
        dragSamples.add(DragSample(nowMs, px, py))
        mode = Mode.DRAG
        X.v = 0.0
        Y.v = 0.0
        Lf.t = 1.0
    }

    fun onMove(px: Double, py: Double, nowMs: Long = System.currentTimeMillis()) {
        if (mode != Mode.DRAG) return
        X.x = rubber(px - dragOx, -S * 0.15, W - S + S * 0.15)
        Y.x = rubber(py - dragOy, -S * 0.15, H - S)
        dragSamples.add(DragSample(nowMs, px, py))
        while (dragSamples.size > 2 && (nowMs - dragSamples.first().t) > 110) {
            dragSamples.removeAt(0)
        }
    }

    fun onUp(nowMs: Long = System.currentTimeMillis()) {
        if (mode != Mode.DRAG) return
        dragId = null
        Lf.t = 0.0
        val v = vel(nowMs)
        val q = geom()
        var best = -1
        var bv = -1e9
        for (i in 0 until N) {
            val n = EDGES[i].n
            val vn = v[0] * n[0] + v[1] * n[1]
            val held = (i == active && commit && vn > -300.0)
            val flick = (vn > config.flickSpeed && q.p[i] > 0.15 && q.d[i] - vn * config.project < 30.0)
            if (held || flick) {
                val s = (if (held) 1e4 else 0.0) + vn
                if (s > bv) {
                    bv = s
                    best = i
                }
            }
        }
        dragSamples.clear()
        if (best >= 0) {
            absorb(best, v)
        } else {
            giveBack(v, nowMs)
        }
    }

    /**
     * Card stretch and scale according to HTML lines 284-290 and Section 5.5.
     */
    data class CardTransform(
        val x: Double,
        val y: Double,
        val scaleX: Double,
        val scaleY: Double,
        val opacity: Double,
        val isVisible: Boolean
    )

    fun getCardTransform(): CardTransform {
        if (mode == Mode.GONE) {
            return CardTransform(
                x = X.x,
                y = Y.x,
                scaleX = 0.0,
                scaleY = 0.0,
                opacity = 0.0,
                isVisible = false
            )
        }
        val sv = clamp(fx[focus].vis.x, 0.0, 1.0)
        val k = if (isReducedMotion) 0.0 else 0.12 * sv * (0.35 + 0.65 * cm)
        val sx = if (focus == 0) 1.0 - k * 0.5 else 1.0 + k
        val sy = if (focus == 0) 1.0 + k else 1.0 - k * 0.5
        val s = max(0.0, Sc.x) * (1.0 + 0.04 * Lf.x)

        var mx = 0.0
        for (i in 0 until N) {
            val v = clamp(fx[i].vis.x, 0.0, 1.0)
            if (v > mx) mx = v
        }
        val opacity = (1.0 - 0.8 * mx) * min(1.0, max(0.0, Sc.x) * 1.6)

        return CardTransform(
            x = X.x,
            y = Y.x,
            scaleX = s * sx,
            scaleY = s * sy,
            opacity = clamp(opacity, 0.0, 1.0),
            isVisible = true
        )
    }

    fun isPointInsideCard(px: Double, py: Double): Boolean {
        if (mode == Mode.ABS || mode == Mode.GONE) return false
        val cx = X.x + S / 2.0
        val cy = Y.x + S / 2.0
        val halfW = (S / 2.0)
        val halfH = (S / 2.0)
        return px >= (cx - halfW) && px <= (cx + halfW) && py >= (cy - halfH) && py <= (cy + halfH)
    }
}
