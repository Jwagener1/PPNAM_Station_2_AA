package com.ppnam.station2aa.domain.usecase

import com.ppnam.station2aa.data.mqtt.EmptyPayload
import com.ppnam.station2aa.data.mqtt.MqttOutcome
import com.ppnam.station2aa.data.mqtt.dto.ActiveJobCardsListResponse
import com.ppnam.station2aa.data.mqtt.dto.ActiveCycleDto
import com.ppnam.station2aa.data.mqtt.dto.ActiveRunDto
import com.ppnam.station2aa.data.mqtt.dto.BomLoadedResponse
import com.ppnam.station2aa.data.mqtt.dto.CollectionResumePayload
import com.ppnam.station2aa.data.mqtt.dto.EquipmentDto
import com.ppnam.station2aa.data.mqtt.dto.LayerInputDto
import com.ppnam.station2aa.data.mqtt.dto.MachineCycleFinishPayload
import com.ppnam.station2aa.data.mqtt.dto.MachineCycleForceClosePayload
import com.ppnam.station2aa.data.mqtt.dto.MachineCycleResultResponse
import com.ppnam.station2aa.data.mqtt.dto.MachineCycleStartPayload
import com.ppnam.station2aa.data.mqtt.dto.MixingOverviewPayload
import com.ppnam.station2aa.data.mqtt.dto.MixingOverviewResponse
import com.ppnam.station2aa.data.mqtt.dto.ReadyMixDto
import com.ppnam.station2aa.domain.model.ActiveCycle
import com.ppnam.station2aa.domain.model.ActiveRun
import com.ppnam.station2aa.domain.model.AreaOverview
import com.ppnam.station2aa.domain.model.CollectedMaterial
import com.ppnam.station2aa.domain.model.Equipment
import com.ppnam.station2aa.domain.model.LayerInput
import com.ppnam.station2aa.domain.model.MachineCycleOutcome
import com.ppnam.station2aa.domain.model.MixingArea
import com.ppnam.station2aa.domain.model.ReadyCollection
import com.ppnam.station2aa.domain.model.ReadyMix
import com.ppnam.station2aa.domain.repository.MqttRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The board's server operations (§7-§9). Every machine-cycle outcome carries the embedded
 * areaStatus so the board refreshes from the response itself — accepted or rejected —
 * with no extra overview round-trip.
 */
@Singleton
class MixingBoardUseCase @Inject constructor(
    private val mqttRepository: MqttRepository,
) {

    suspend fun fetchOverview(
        area: MixingArea? = null,
        jobCardNumber: String? = null,
    ): Result<AreaOverview> =
        when (
            val outcome = mqttRepository.request(
                requestType = "mixing_overview_requested",
                responseType = "mixing_overview_result",
                payload = MixingOverviewPayload(
                    mixingArea = area?.wire,
                    productionOrderDocumentNumber = jobCardNumber,
                ),
                correlationKey = jobCardNumber,
                responseClass = MixingOverviewResponse::class.java,
            )
        ) {
            is MqttOutcome.Accepted -> Result.success(outcome.body.toAreaOverview())
            is MqttOutcome.Rejected -> Result.failure(Exception(outcome.reason ?: "Overview rejected"))
            is MqttOutcome.NoResponse -> Result.failure(Exception(outcome.kind.message()))
        }

    suspend fun fetchReadyCollections(): Result<List<ReadyCollection>> =
        when (
            val outcome = mqttRepository.request(
                requestType = "active_job_cards_requested",
                responseType = "active_job_cards_list",
                payload = EmptyPayload,
                correlationKey = null,
                responseClass = ActiveJobCardsListResponse::class.java,
            )
        ) {
            is MqttOutcome.Accepted -> Result.success(
                outcome.body.jobs
                    .filter { it.status == "ReadyForMixing" }
                    .map { ReadyCollection(it.collectionId, it.jobCardNumber, it.productName) }
            )
            is MqttOutcome.Rejected -> Result.failure(Exception(outcome.reason ?: "Could not load collections"))
            is MqttOutcome.NoResponse -> Result.failure(Exception(outcome.kind.message()))
        }

    /**
     * The Rajoo dose sheet's rows: the collection's collected manual lines. Uses the §12
     * capture action `collection_resume_requested` — resuming a ReadyForMixing collection
     * replays its stored snapshot without touching state.
     */
    suspend fun fetchCollectedMaterials(
        jobCardNumber: String,
        collectionId: String,
    ): Result<List<CollectedMaterial>> =
        when (
            val outcome = mqttRepository.request(
                requestType = "collection_resume_requested",
                responseType = "bom_loaded",
                payload = CollectionResumePayload(jobCardNumber = jobCardNumber, collectionId = collectionId),
                correlationKey = collectionId,
                responseClass = BomLoadedResponse::class.java,
            )
        ) {
            is MqttOutcome.Accepted -> Result.success(
                outcome.body.ingredients
                    .filter { it.issueType != "im_Backflush" && it.collectedQuantity > 0.0 }
                    .map { CollectedMaterial(it.materialCode, it.materialName, it.collectedQuantity) }
            )
            is MqttOutcome.Rejected -> Result.failure(Exception(outcome.reason ?: "Could not load collection"))
            is MqttOutcome.NoResponse -> Result.failure(Exception(outcome.kind.message()))
        }

    suspend fun startMixer(machineCode: String, jobCardNumber: String, collectionId: String): MachineCycleOutcome =
        cycleRequest(
            "machine_cycle_start_requested",
            MachineCycleStartPayload(
                machineCode = machineCode,
                productionOrderDocumentNumber = jobCardNumber,
                collectionId = collectionId,
            ),
            correlationKey = collectionId,
        )

    suspend fun startRajoo(
        machineCode: String,
        jobCardNumber: String,
        collectionId: String,
        doses: List<LayerInput>,
    ): MachineCycleOutcome =
        cycleRequest(
            "machine_cycle_start_requested",
            MachineCycleStartPayload(
                machineCode = machineCode,
                productionOrderDocumentNumber = jobCardNumber,
                collectionId = collectionId,
                layerInputs = doses.map { LayerInputDto(it.materialCode, it.dosingQuantity) },
            ),
            correlationKey = collectionId,
        )

    suspend fun startDownstream(machineCode: String, jobCardNumber: String, mixBatchIds: List<String>): MachineCycleOutcome =
        cycleRequest(
            "machine_cycle_start_requested",
            MachineCycleStartPayload(
                machineCode = machineCode,
                productionOrderDocumentNumber = jobCardNumber,
                mixBatchIds = mixBatchIds,
            ),
            correlationKey = mixBatchIds.firstOrNull(),
        )

    suspend fun finish(machineCode: String, cycleId: String): MachineCycleOutcome =
        cycleRequest(
            "machine_cycle_finish_requested",
            MachineCycleFinishPayload(machineCode = machineCode, cycleId = cycleId),
            correlationKey = cycleId,
        )

    suspend fun forceClose(
        machineCode: String,
        cycleId: String,
        managerUsername: String,
        managerPassword: String,
        auditReason: String,
    ): MachineCycleOutcome =
        cycleRequest(
            "machine_cycle_force_close_requested",
            MachineCycleForceClosePayload(
                machineCode = machineCode,
                cycleId = cycleId,
                managerUsername = managerUsername,
                managerPassword = managerPassword,
                auditReason = auditReason,
            ),
            correlationKey = cycleId,
        )

    private suspend fun cycleRequest(requestType: String, payload: Any, correlationKey: String?): MachineCycleOutcome =
        when (
            val outcome = mqttRepository.request(
                requestType = requestType,
                responseType = "machine_cycle_result",
                payload = payload,
                correlationKey = correlationKey,
                responseClass = MachineCycleResultResponse::class.java,
            )
        ) {
            is MqttOutcome.Accepted -> MachineCycleOutcome.Accepted(
                action = outcome.body.action,
                machineCode = outcome.body.machineCode.orEmpty(),
                cycleId = outcome.body.cycleId,
                mixBatchId = outcome.body.mixBatchId,
                productionRunId = outcome.body.productionRunId,
                affectedMixBatchIds = outcome.body.affectedMixBatchIds,
                alreadyFinished = outcome.body.alreadyFinished,
                forceClosed = outcome.body.forceClosed,
                approverDisplayName = outcome.body.approverDisplayName,
                areaStatus = outcome.body.areaStatus.toAreaOverview(),
            )
            is MqttOutcome.Rejected -> MachineCycleOutcome.Rejected(
                errorCode = outcome.errorCode,
                reason = outcome.reason ?: "Machine cycle rejected",
                areaStatus = outcome.body.areaStatus.toAreaOverview().takeUnless {
                    // Envelope-level rejections carry no areaStatus; Gson defaults it to
                    // empty. A real business rejection always embeds equipment (§8).
                    it.equipment.isEmpty() && it.activeCycles.isEmpty() &&
                        it.readyMixes.isEmpty() && it.activeRuns.isEmpty()
                },
            )
            is MqttOutcome.NoResponse -> MachineCycleOutcome.Failed(outcome.kind.message())
        }

    // ---- DTO -> domain -------------------------------------------------------

    private fun MixingOverviewResponse.toAreaOverview() = AreaOverview(
        equipment = equipment.map { it.toEquipment() },
        activeCycles = activeCycles.map { it.toActiveCycle() },
        readyMixes = readyMixes.map { it.toReadyMix() },
        activeRuns = activeRuns.map { it.toActiveRun() },
    )

    private fun EquipmentDto.toEquipment() = Equipment(
        machineCode = machineCode,
        displayName = displayName,
        area = MixingArea.fromWire(mixingArea),
        role = equipmentRole,
        isEnabled = isEnabled,
        isAvailable = isAvailable,
        status = status,
        productLayer = productLayer,
        currentCycleId = currentCycleId,
        currentJobCardNumber = currentProductionOrderDocumentNumber,
        currentMixBatchIds = currentMixBatchIds,
        validDestinationMachineCodes = validDestinationMachineCodes,
        routeDescription = routeDescription,
    )

    private fun ReadyMixDto.toReadyMix() = ReadyMix(
        mixBatchId = mixBatchId,
        collectionId = collectionId,
        area = MixingArea.fromWire(mixingArea),
        jobCardNumber = productionOrderDocumentNumber,
        mixerCode = mixerCode,
        mixerDisplayName = mixerDisplayName,
        status = status,
        validNextMachineCodes = validNextMachineCodes,
        nextStepDescription = nextStepDescription,
    )

    private fun ActiveCycleDto.toActiveCycle() = ActiveCycle(
        cycleId = cycleId,
        machineCode = machineCode,
        area = MixingArea.fromWire(mixingArea),
        role = equipmentRole,
        jobCardNumber = productionOrderDocumentNumber,
        collectionId = collectionId,
        mixBatchIds = mixBatchIds,
        productionRunId = productionRunId,
        startedAtUtc = startedAtUtc,
        startedByOperatorId = startedByOperatorId,
    )

    private fun ActiveRunDto.toActiveRun() = ActiveRun(
        productionRunId = productionRunId,
        machineCode = machineCode,
        jobCardNumber = productionOrderDocumentNumber,
        mixBatchIds = mixBatchIds,
        startedAtUtc = startedAtUtc,
    )
}
