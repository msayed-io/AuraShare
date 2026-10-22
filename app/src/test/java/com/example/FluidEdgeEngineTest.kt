package com.example

import com.example.ui.engine.FluidEdgeEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FluidEdgeEngineTest {

    private val tol = 0.01

    private fun assertClose(expected: Double, actual: Double, message: String = "") {
        assertTrue(
            "$message expected <$expected> but was <$actual> (diff = ${abs(expected - actual)})",
            abs(expected - actual) <= tol
        )
    }

    // 9.1 النابض (dt = 1/60)
    @Test
    fun spring_caseA_matches_golden_values() {
        // A: x0=0, t=100, r=0.5, z=1
        val s = FluidEdgeEngine.Spring(x = 0.0, r = 0.5, z = 1.0)
        s.t = 100.0
        val dt = 1.0 / 60.0

        val checkpoints = mapOf(
            1 to Pair(2.6504, 226.5628),
            5 to Pair(30.3506, 466.178),
            10 to Pair(63.3302, 315.5749),
            20 to Pair(91.9791, 78.4757),
            30 to Pair(98.4081, 16.0499),
            60 to Pair(99.9887, 0.1153)
        )

        for (n in 1..60) {
            s.step(dt)
            checkpoints[n]?.let { (expX, expV) ->
                assertClose(expX, s.x, "Case A at n=$n (x)")
                assertClose(expV, s.v, "Case A at n=$n (v)")
            }
        }
    }

    @Test
    fun spring_caseB_matches_golden_values() {
        // B: x0=200, t=-84, v0=-1800, r=0.42, z=1
        val s = FluidEdgeEngine.Spring(x = 200.0, r = 0.42, z = 1.0)
        s.t = -84.0
        s.v = -1800.0
        val dt = 1.0 / 60.0

        val checkpoints = mapOf(
            1 to Pair(168.6679, -1899.8214),
            5 to Pair(56.0726, -1401.8346),
            10 to Pair(-25.1841, -656.8155),
            20 to Pair(-74.9548, -106.8813),
            30 to Pair(-82.6738, -15.8346),
            60 to Pair(-83.996, -0.0485)
        )

        for (n in 1..60) {
            s.step(dt)
            checkpoints[n]?.let { (expX, expV) ->
                assertClose(expX, s.x, "Case B at n=$n (x)")
                assertClose(expV, s.v, "Case B at n=$n (v)")
            }
        }
    }

    @Test
    fun spring_caseC_matches_golden_values() {
        // C: x0=40, t=300, v0=900, r=0.5, z=0.82
        val s = FluidEdgeEngine.Spring(x = 40.0, r = 0.5, z = 0.82)
        s.t = 300.0
        s.v = 900.0
        val dt = 1.0 / 60.0

        val checkpoints = mapOf(
            1 to Pair(58.7487, 1218.9144),
            5 to Pair(153.0055, 1385.415),
            10 to Pair(243.0069, 787.3889),
            20 to Pair(299.3403, 79.2318),
            30 to Pair(301.3985, -10.1772),
            60 to Pair(299.9934, 0.0652)
        )

        for (n in 1..60) {
            s.step(dt)
            checkpoints[n]?.let { (expX, expV) ->
                assertClose(expX, s.x, "Case C at n=$n (x)")
                assertClose(expV, s.v, "Case C at n=$n (v)")
            }
        }
    }

    @Test
    fun spring_caseD_matches_golden_values() {
        // Case D: r=0.34, z=1, x0=0, t=1, 1 step at dt=0.033 -> x=0.1569, v=6.506
        val s = FluidEdgeEngine.Spring(x = 0.0, r = 0.34, z = 1.0)
        s.t = 1.0
        s.step(0.033)
        assertClose(0.1569, s.x, "Case D (x)")
        assertClose(6.506, s.v, "Case D (v)")
    }

    // 9.2 الأدوات:
    // ss(0.25)=0.1563, ss(0.5)=0.5, ss(0.75)=0.8438, rubber(-50, -21, 999)=-29.7, rubber(1000, 0, 900)=930, lin(250,1600,925)=0.5
    @Test
    fun tools_match_golden_values() {
        assertClose(0.1563, FluidEdgeEngine.ss(0.25), "ss(0.25)")
        assertClose(0.5, FluidEdgeEngine.ss(0.5), "ss(0.5)")
        assertClose(0.8438, FluidEdgeEngine.ss(0.75), "ss(0.75)")
        assertClose(-29.7, FluidEdgeEngine.rubber(-50.0, -21.0, 999.0), "rubber(-50, -21, 999)")
        assertClose(930.0, FluidEdgeEngine.rubber(1000.0, 0.0, 900.0), "rubber(1000, 0, 900)")
        assertClose(0.5, FluidEdgeEngine.lin(250.0, 1600.0, 925.0), "lin(250, 1600, 925)")
    }

    // 9.3 أبعاد السائل (S=140, v=0.5, c=1, d=60, Sc.x=1)
    // vd=0.6598, dw=88.2, sz=299.1263, near=116, len=155.7019, lv=0.6836
    @Test
    fun liquid_dimensions_match_golden_values() {
        val S = 140.0
        val v = 0.5
        val c = 1.0
        val d = 60.0
        val scX = 1.0

        val vd = Math.pow(v, 0.6)
        val dw = S * (0.28 + 0.5 * v + 0.1 * c)
        val sz = 150.0 + 190.0 * Math.pow(v, 0.8) + 40.0 * c
        val near = Math.max(0.0, d + S * Math.max(0.0, scX) * 0.4)
        val len = (120.0 + near) * vd
        val lv = FluidEdgeEngine.ss(v * 1.25)

        assertClose(0.6598, vd, "vd")
        assertClose(88.2, dw, "dw")
        assertClose(299.1263, sz, "sz")
        assertClose(116.0, near, "near")
        assertClose(155.7019, len, "len")
        assertClose(0.6836, lv, "lv")
    }

    // 9.4 الموجة (amp=1):
    // t=0.25 -> r=213.4375, opacity=0.3679
    // t=0.5 -> r=302.5, opacity=0.1847
    // t=1 -> r=340, opacity=0
    @Test
    fun wave_matches_golden_values() {
        fun calcWave(t: Double, amp: Double = 1.0): Pair<Double, Double> {
            val r = FluidEdgeEngine.lerp(40.0, 340.0, 1.0 - Math.pow(1.0 - t, 3.0))
            val opacity = amp * Math.pow(1.0 - t, 1.7) * 0.6
            return Pair(r, opacity)
        }

        val w025 = calcWave(0.25)
        assertClose(213.4375, w025.first, "wave r at t=0.25")
        assertClose(0.3679, w025.second, "wave opacity at t=0.25")

        val w05 = calcWave(0.5)
        assertClose(302.5, w05.first, "wave r at t=0.5")
        assertClose(0.1847, w05.second, "wave opacity at t=0.5")

        val w10 = calcWave(1.0)
        assertClose(340.0, w10.first, "wave r at t=1.0")
        assertClose(0.0, w10.second, "wave opacity at t=1.0")
    }
}
