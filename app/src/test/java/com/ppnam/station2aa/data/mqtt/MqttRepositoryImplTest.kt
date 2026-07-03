package com.ppnam.station2aa.data.mqtt

import com.ppnam.station2aa.data.local.OfflineQueueDao
import com.ppnam.station2aa.data.local.OfflineQueueEntity
import com.ppnam.station2aa.data.mqtt.dto.OperatorContextResponse
import com.ppnam.station2aa.data.settings.SettingsRepository
import com.ppnam.station2aa.domain.model.HopperAvailability
import com.ppnam.station2aa.domain.repository.MqttConnectionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class MqttRepositoryImplTest {

    private lateinit var mockClientFactory: MqttClientFactory
    private lateinit var mockSettingsRepository: SettingsRepository
    private lateinit var mockQueueDao: OfflineQueueDao
    private lateinit var repo: MqttRepositoryImpl

    @Before
    fun setup() {
        mockClientFactory = mock()
        mockSettingsRepository = mock()
        mockQueueDao = mock()
        repo = MqttRepositoryImpl(mockClientFactory, mockSettingsRepository, mockQueueDao)
    }

    @Test
    fun `initial connection state is DISCONNECTED`() = runTest {
        assertEquals(MqttConnectionState.DISCONNECTED, repo.connectionState.first())
    }

    @Test
    fun `send fails fast when disconnected instead of queuing`() = runTest {
        val result = repo.sendWithTimeout("complete-premix", "{}", timeoutMs = 100L)
        assertTrue(result is MqttResult.Error)
        verify(mockQueueDao, never()).insert(any())
    }

    @Test
    fun `send fails fast when disconnected regardless of action`() = runTest {
        val result = repo.sendWithTimeout("lookup-pallet", "{}", timeoutMs = 100L)
        assertTrue(result is MqttResult.Error)
        verify(mockQueueDao, never()).insert(any())
    }

    @Test
    fun `hopperStatusUpdates emits parsed HopperStatus on hopper topic message`() = runTest {
        val json = """{"hopperCode":"H-01","status":"AVAILABLE","assignedTo":null}"""
        val method = MqttRepositoryImpl::class.java.getDeclaredMethod("handleHopperStatus", ByteArray::class.java)
        method.isAccessible = true
        method.invoke(repo, json.toByteArray())

        val emitted = repo.hopperStatusUpdates.replayCache.firstOrNull()
        assertNotNull(emitted)
        assertEquals("H-01", emitted!!.hopperCode)
        assertEquals(HopperAvailability.AVAILABLE, emitted.status)
    }

    @Test
    fun `hopperStatusUpdates does not crash on malformed payload`() = runTest {
        val method = MqttRepositoryImpl::class.java.getDeclaredMethod("handleHopperStatus", ByteArray::class.java)
        method.isAccessible = true
        method.invoke(repo, "not-json".toByteArray())
        assertTrue(repo.hopperStatusUpdates.replayCache.isEmpty())
    }

    @Test
    fun `sendTyped returns Disconnected when offline queue not allowed`() = runTest {
        val result = repo.sendTyped(
            requestType = "reader_login_requested",
            responseType = "operator_context",
            requestJson = "{}",
            responseClass = OperatorContextResponse::class.java,
            allowOfflineQueue = false
        )
        assertTrue(result is MqttTypedResult.Disconnected)
        verify(mockQueueDao, never()).insert(any())
    }

    @Test
    fun `publishTyped is a silent no-op when disconnected`() = runTest {
        repo.publishTyped("premix_cancelled", "{}")
        verify(mockQueueDao, never()).insert(any())
    }

    @Test
    fun `sendTyped queues when disconnected and offline queue allowed`() = runTest {
        whenever(mockQueueDao.insert(any())).thenReturn(Unit)
        val result = repo.sendTyped(
            requestType = "ingredient_scanned",
            responseType = "ingredient_scan_result",
            requestJson = "{\"qty\":5}",
            responseClass = OperatorContextResponse::class.java,
            allowOfflineQueue = true
        )
        assertTrue(result is MqttTypedResult.Queued)
        verify(mockQueueDao).insert(
            argThat<OfflineQueueEntity> { action == "ingredient_scanned" && payload == "{\"qty\":5}" }
        )
    }
}
