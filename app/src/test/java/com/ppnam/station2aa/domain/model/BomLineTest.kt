package com.ppnam.station2aa.domain.model

import org.junit.Assert.*
import org.junit.Test

class BomLineTest {

    @Test
    fun `isFullyAllocated is true when remainingQty is zero`() {
        val line = BomLine(itemCode = "MAT-001", itemName = "Resin", requiredQty = 50.0, remainingQty = 0.0)
        assertTrue(line.isFullyAllocated)
    }

    @Test
    fun `isFullyAllocated is true when remainingQty is negative`() {
        val line = BomLine(itemCode = "MAT-001", itemName = "Resin", requiredQty = 50.0, remainingQty = -1.5)
        assertTrue(line.isFullyAllocated)
    }

    @Test
    fun `isFullyAllocated is false when remainingQty is positive`() {
        val line = BomLine(itemCode = "MAT-001", itemName = "Resin", requiredQty = 50.0, remainingQty = 12.0)
        assertFalse(line.isFullyAllocated)
    }

    @Test
    fun `isFullyAllocated defaults to true when remainingQty is not specified`() {
        val line = BomLine(itemCode = "MAT-001", itemName = "Resin", requiredQty = 50.0)
        assertTrue(line.isFullyAllocated)
    }
}
