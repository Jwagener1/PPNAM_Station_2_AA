package com.ppnam.station2aa.ui.home

import com.ppnam.station2aa.data.session.OperatorSession
import com.ppnam.station2aa.data.session.OperatorSessionHolder
import com.ppnam.station2aa.domain.repository.MqttConnectionState
import com.ppnam.station2aa.domain.repository.MqttRepository
import com.ppnam.station2aa.domain.usecase.AuthUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class HomeViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var mockMqttRepository: MqttRepository
    private lateinit var mockAuthUseCase: AuthUseCase
    private lateinit var mockSessionHolder: OperatorSessionHolder
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockMqttRepository = mock()
        mockAuthUseCase = mock()
        mockSessionHolder = mock()

        whenever(mockMqttRepository.connectionState)
            .thenReturn(MutableStateFlow(MqttConnectionState.DISCONNECTED))
        whenever(mockMqttRepository.stationOnline).thenReturn(MutableStateFlow(true))
        whenever(mockMqttRepository.clockSkewMillis).thenReturn(MutableStateFlow<Long?>(null))
        whenever(mockSessionHolder.session).thenReturn(
            MutableStateFlow(OperatorSession("sess-1", "OP-1", "Jane Smith", "Operator"))
        )

        viewModel = HomeViewModel(mockMqttRepository, mockAuthUseCase, mockSessionHolder)
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `session reflects the operator session holder`() = runTest {
        assertEquals("Jane Smith", viewModel.session.value?.operatorName)
    }

    @Test
    fun `logout calls AuthUseCase and fires logoutEvent`() = runTest {
        whenever(mockAuthUseCase.logout()).thenReturn(Result.success(Unit))

        val events = mutableListOf<Unit>()
        val job = launch(testDispatcher) { viewModel.logoutEvent.collect { events.add(it) } }

        viewModel.logout()
        advanceUntilIdle()

        verify(mockAuthUseCase).logout()
        assertEquals(1, events.size)
        job.cancel()
    }
}
