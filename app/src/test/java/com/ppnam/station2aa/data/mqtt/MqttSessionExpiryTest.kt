package com.ppnam.station2aa.data.mqtt

import com.ppnam.station2aa.data.session.OperatorSession
import com.ppnam.station2aa.data.session.OperatorSessionHolder
import com.ppnam.station2aa.data.settings.SettingsRepository
import com.ppnam.station2aa.domain.repository.MqttConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

class MqttSessionExpiryTest {

    private lateinit var repo: MqttRepositoryImpl
    private lateinit var sessionHolder: OperatorSessionHolder
    private val published = mutableListOf<Pair<String, ByteArray>>()

    @Before
    fun setup() {
        sessionHolder = OperatorSessionHolder()
        sessionHolder.set(
            OperatorSession(
                operatorSessionId = "session-abc",
                operatorId = "OP-001",
                operatorName = "Operator One",
                role = "Operator",
            )
        )
        repo = MqttRepositoryImpl(
            clientFactory = mock(),
            settingsRepository = mock<SettingsRepository>(),
            sessionHolder = sessionHolder,
        )
        published.clear()
        repo.publishFn = { topic, bytes -> published += topic to bytes }
        val field = MqttRepositoryImpl::class.java.getDeclaredField("_connectionState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(repo) as MutableStateFlow<MqttConnectionState>).value = MqttConnectionState.CONNECTED
    }

    private fun messageIdOf(index: Int): String =
        com.google.gson.JsonParser
            .parseString(String(published[index].second)).asJsonObject.get("messageId").asString

    private suspend fun CoroutineScope.requestAndRespond(errorCode: String?) {
        val call = async {
            repo.request("a_requested", "test_result", EmptyPayload, null, TestBody::class.java)
        }
        while (published.isEmpty()) yield()
        val id = messageIdOf(0)
        val codeJson = errorCode?.let { "\"$it\"" } ?: "null"
        repo.handleIncomingResponse(
            "PPNAM/handheld_1/res/test_result",
            """{"inResponseToMessageId":"$id","accepted":false,"errorCode":$codeJson,"reason":"x"}""".toByteArray()
        )
        call.await()
    }

    @Test
    fun `a session_required rejection clears the local session`() = runTest {
        assertNotNull(sessionHolder.session.value)

        requestAndRespond("session_required")

        assertNull("session_required must clear the session", sessionHolder.session.value)
    }

    @Test
    fun `an unrelated rejection leaves the session intact`() = runTest {
        // Only session_required means the session is gone. A validation failure must not log the
        // operator out.
        requestAndRespond("validation_failed")

        assertNotNull(sessionHolder.session.value)
    }

    @Test
    fun `a rejection with no error code leaves the session intact`() = runTest {
        requestAndRespond(null)

        assertNotNull(sessionHolder.session.value)
    }

    @Test
    fun `an accepted response leaves the session intact`() = runTest {
        val call = async {
            repo.request("a_requested", "test_result", EmptyPayload, null, TestBody::class.java)
        }
        while (published.isEmpty()) yield()
        repo.handleIncomingResponse(
            "PPNAM/handheld_1/res/test_result",
            """{"inResponseToMessageId":"${messageIdOf(0)}","accepted":true}""".toByteArray()
        )
        call.await()

        assertNotNull(sessionHolder.session.value)
    }
}
