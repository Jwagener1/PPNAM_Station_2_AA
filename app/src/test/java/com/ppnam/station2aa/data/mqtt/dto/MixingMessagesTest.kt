package com.ppnam.station2aa.data.mqtt.dto

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class MixingMessagesTest {

    private val gson = Gson()

    // Field names copied from the simulator's payload builders (handlers/mixing.py),
    // which mirror the contract §7/§8 examples.
    private val overviewJson = """
        {
          "mixingArea": "JandiBulkMixing",
          "equipment": [{
            "mixingArea": "JandiBulkMixing", "equipmentRole": "Mixer",
            "machineCode": "JAN-MIX-01", "displayName": "JANDI 2/3 Shared Bulk Mixer",
            "isEnabled": true, "isAvailable": true, "status": "Available",
            "productLayer": null, "currentCycleId": null,
            "currentJobCardNumber": null, "currentMixBatchIds": [],
            "validDestinationMachineCodes": ["JAN-02", "JAN-03", "JAN-04"],
            "routeDescription": "JANDI 2 or JANDI 3 direct; use the drum cycle before JANDI 4."
          }],
          "activeCycles": [{
            "cycleId": "CYC_000007", "machineCode": "JAN-MIX-01",
            "mixingArea": "JandiBulkMixing", "equipmentRole": "Mixer",
            "jobCardNumber": "510019068", "collectionId": "COL_000003",
            "mixBatchIds": ["MIX_000003"], "productionRunId": null,
            "startedAtUtc": "2026-07-21T08:20:00Z", "startedByOperatorId": "OP-001"
          }],
          "readyMixes": [{
            "mixBatchId": "MIX_000001", "collectionId": "COL_000001",
            "mixingArea": "JandiBulkMixing", "jobCardNumber": "510019068",
            "sourceMixerCode": "JAN-MIX-01", "mixerDisplayName": "JANDI 2/3 Shared Bulk Mixer",
            "productLayer": null, "status": "ReadyForProduction",
            "validNextMachineCodes": ["JAN-DRUM-01", "JAN-02", "JAN-03"],
            "nextStepDescription": "Start one of: JAN-DRUM-01, JAN-02, JAN-03."
          }],
          "activeRuns": [{
            "productionRunId": "RUN_000001", "machineCode": "EXT-03",
            "jobCardNumber": "510019068",
            "mixBatchIds": ["MIX_000001"], "startedAtUtc": "2026-07-21T08:30:00Z"
          }]
        }
    """.trimIndent()

    @Test
    fun `overview response parses the simulator shape`() {
        val r = gson.fromJson(overviewJson, MixingOverviewResponse::class.java)
        assertEquals("JandiBulkMixing", r.mixingArea)
        val eq = r.equipment.single()
        assertEquals("JAN-MIX-01", eq.machineCode)
        assertTrue(eq.isEnabled && eq.isAvailable)
        assertNull(eq.productLayer)
        assertEquals(listOf("JAN-02", "JAN-03", "JAN-04"), eq.validDestinationMachineCodes)
        assertEquals("CYC_000007", r.activeCycles.single().cycleId)
        assertEquals(listOf("JAN-DRUM-01", "JAN-02", "JAN-03"), r.readyMixes.single().validNextMachineCodes)
        assertEquals("RUN_000001", r.activeRuns.single().productionRunId)
    }

    @Test
    fun `machine cycle result parses with embedded areaStatus and nullable ids`() {
        val json = """
            {
              "action": "Started", "mixingArea": "MainMixingRoom", "equipmentRole": "Mixer",
              "machineCode": "MXR-01", "cycleId": "CYC_000001",
              "jobCardNumber": "510019068", "collectionId": "COL_000001",
              "mixBatchId": "MIX_000001", "productionRunId": null,
              "affectedMixBatchIds": ["MIX_000001"], "alreadyFinished": false,
              "forceClosed": false, "approverUserId": null, "approverDisplayName": null,
              "approverRole": null, "sapIssueQueued": false, "sapProductionOrderChanged": false,
              "areaStatus": $overviewJson
            }
        """.trimIndent()
        val r = gson.fromJson(json, MachineCycleResultResponse::class.java)
        assertEquals("Started", r.action)
        assertEquals("CYC_000001", r.cycleId)
        assertNull(r.productionRunId)
        assertFalse(r.alreadyFinished)
        assertEquals(1, r.areaStatus.equipment.size)
    }

    @Test
    fun `start payload omits absent optional fields when serialized`() {
        val json = gson.toJson(MachineCycleStartPayload(
            machineCode = "MXR-01", productionOrderDocumentNumber = "510019068",
            collectionId = "COL_000001"))
        assertFalse("mixBatchIds must be omitted, not null", json.contains("mixBatchIds"))
        assertFalse("layerInputs must be omitted, not null", json.contains("layerInputs"))
        assertTrue(json.contains("\"collectionId\":\"COL_000001\""))
    }

    @Test
    fun `mixing area maps wire values both ways`() {
        assertEquals(com.ppnam.station2aa.domain.model.MixingArea.Jandi,
            com.ppnam.station2aa.domain.model.MixingArea.fromWire("JandiBulkMixing"))
        assertNull(com.ppnam.station2aa.domain.model.MixingArea.fromWire("Atlantis"))
        assertEquals(5, com.ppnam.station2aa.domain.model.MixingArea.entries.size)
    }
}
