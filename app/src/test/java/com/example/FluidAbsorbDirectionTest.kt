package com.example

import com.example.ui.engine.FluidEdgeEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/** Uses the existing engine unchanged, including its real absorption completion. */
class FluidAbsorbDirectionTest {
    @Test fun absorption_reports_each_physical_edge_once_in_normal_and_reduced_motion() {
        for (reduced in listOf(false, true)) {
            for ((index, name) in listOf("top", "left", "right").withIndex()) {
                val absorbed = mutableListOf<String>()
                val engine = FluidEdgeEngine(isReducedMotion = reduced, onAbsorb = { absorbed += it })
                engine.resize(400.0, 800.0)
                engine.X.set(engine.hx)
                engine.Y.set(engine.hy)
                engine.absorb(index, doubleArrayOf(0.0, 0.0))
                for (frame in 1..600) {
                    engine.step(1.0 / 60.0, frame * 16L)
                    engine.sense(1.0 / 60.0, frame * 16L)
                    if (absorbed.isNotEmpty()) break
                }
                assertEquals("$name reduced=$reduced", listOf(name), absorbed)
                repeat(10) { engine.step(1.0 / 60.0, 10_000L + it * 16L) }
                assertEquals(listOf(name), absorbed)
            }
        }
    }
}
