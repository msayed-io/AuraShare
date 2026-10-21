package com.example

import com.example.ui.components.fallbackBridgeGeometry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FluidFallbackGeometryTest {
    @Test
    fun nearCardProducesFiniteBridgeThatWidensTowardEdge() {
        val near = fallbackBridgeGeometry(cardCenterX = 120f, cardCenterY = 30f, cardRadius = 46f)
        val farther = fallbackBridgeGeometry(cardCenterX = 120f, cardCenterY = 120f, cardRadius = 46f)

        assertNotNull(near)
        assertNotNull(farther)
        assertTrue(near!!.anchorHalfWidth > farther!!.anchorHalfWidth)
        assertTrue(near.cardHalfWidth > farther.cardHalfWidth)
        listOf(
            near.anchorX, near.anchorY, near.cardX, near.cardY,
            near.anchorHalfWidth, near.cardHalfWidth
        ).forEach { assertTrue(it.isFinite()) }
    }

    @Test
    fun distantOrInvalidInputsDoNotCreateGeometry() {
        assertNull(fallbackBridgeGeometry(120f, 150f, 46f))
        assertNull(fallbackBridgeGeometry(Float.NaN, 30f, 46f))
        assertNull(fallbackBridgeGeometry(120f, 30f, Float.POSITIVE_INFINITY))
    }
}
