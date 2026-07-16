package com.ppnam.station2aa.domain.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HopperBoardTest {

    private val gson = Gson()

    @Test
    fun `parses an abbreviated board entry`() {
        // Several contract responses carry only these four fields.
        val json = """
            {"displayName":"Hopper 1","machineCode":"MXR-01","status":"Available","isAvailable":true}
        """.trimIndent()

        val entry = gson.fromJson(json, HopperBoardEntry::class.java)

        assertEquals("Hopper 1", entry.displayName)
        assertEquals("MXR-01", entry.machineCode)
        assertEquals(HopperState.Available, entry.status)
        assertTrue(entry.isAvailable)
        assertNull(entry.cycleId)
        assertNull(entry.collectionId)
        assertNull(entry.preMixId)
        assertNull(entry.inactiveReason)
    }

    @Test
    fun `parses a full in-use board entry`() {
        val json = """
            {
              "displayName": "Hopper 1",
              "machineCode": "MXR-01",
              "status": "InUse",
              "isAvailable": false,
              "cycleId": "CYC_000601",
              "collectionId": "COL_000123",
              "preMixId": "PMX_000090",
              "jobCardNumber": "510019068",
              "assignedAtUtc": "2026-07-16T10:00:01Z",
              "assignedByOperatorId": "OP-001",
              "assignedByDisplayName": "Operator One",
              "assignedFromDevice": "handheld_1",
              "inactiveReason": null
            }
        """.trimIndent()

        val entry = gson.fromJson(json, HopperBoardEntry::class.java)

        assertEquals(HopperState.InUse, entry.status)
        assertFalse(entry.isAvailable)
        assertEquals("CYC_000601", entry.cycleId)
        assertEquals("COL_000123", entry.collectionId)
        assertEquals("PMX_000090", entry.preMixId)
        assertEquals("510019068", entry.jobCardNumber)
        assertEquals("2026-07-16T10:00:01Z", entry.assignedAtUtc)
        assertEquals("OP-001", entry.assignedByOperatorId)
        assertEquals("Operator One", entry.assignedByDisplayName)
        assertEquals("handheld_1", entry.assignedFromDevice)
    }

    @Test
    fun `parses an inactive board entry with its reason`() {
        val json = """
            {"displayName":"Hopper 3","machineCode":"MXR-03","status":"Inactive",
             "isAvailable":false,"inactiveReason":"Under maintenance"}
        """.trimIndent()

        val entry = gson.fromJson(json, HopperBoardEntry::class.java)

        assertEquals(HopperState.Inactive, entry.status)
        assertFalse(entry.isAvailable)
        assertEquals("Under maintenance", entry.inactiveReason)
    }

    @Test
    fun `every contract hopper status maps from its wire value`() {
        assertEquals(HopperState.Available, gson.fromJson("\"Available\"", HopperState::class.java))
        assertEquals(HopperState.InUse, gson.fromJson("\"InUse\"", HopperState::class.java))
        assertEquals(HopperState.Inactive, gson.fromJson("\"Inactive\"", HopperState::class.java))
    }

    @Test
    fun `a board list parses`() {
        val json = """
            [{"displayName":"Hopper 1","machineCode":"MXR-01","status":"Available","isAvailable":true},
             {"displayName":"Hopper 2","machineCode":"MXR-02","status":"InUse","isAvailable":false}]
        """.trimIndent()

        val board = gson.fromJson(json, Array<HopperBoardEntry>::class.java).toList()

        assertEquals(2, board.size)
        assertEquals(HopperState.Available, board[0].status)
        assertEquals(HopperState.InUse, board[1].status)
    }
}
