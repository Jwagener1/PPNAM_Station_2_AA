package com.ppnam.station2aa.data.mqtt

import com.ppnam.station2aa.data.local.OfflineQueueDao
import com.ppnam.station2aa.data.settings.SettingsRepository
import com.ppnam.station2aa.domain.model.AppSettings
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
    private val fakeDeviceId = "test-device-id"

    @Before
    fun setup() {
        mockClientFactory = mock()
        mockSettingsRepository = mock()
        mockQueueDao = mock()
        repo = MqttRepositoryImpl(mockClientFactory, mockSettingsRepository, mockQueueDao, fakeDeviceId)
    }

    @Test
    fun `initial connection state is DISCONNECTED`() = runTest {
        assertEquals(MqttConnectionState.DISCONNECTED, repo.connectionState.first())
    }

    @Test
    fun `send queues message when disconnected`() = runTest {
        whenever(mockQueueDao.insert(any())).thenReturn(Unit)
        val result = repo.sendWithTimeout("complete-premix", "{}", timeoutMs = 100L)
        assertTrue(result is MqttResult.Queued)
        verify(mockQueueDao).insert(any())
    }

    @Test
    fun `send queues message on timeout`() = runTest {
        whenever(mockQueueDao.insert(any())).thenReturn(Unit)
        val result = repo.sendWithTimeout("lookup-job", "{}", timeoutMs = 100L)
        assertTrue(result is MqttResult.Queued)
    }
}
