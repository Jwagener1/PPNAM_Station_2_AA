package com.ppnam.station2aa.data.mqtt

import com.google.gson.JsonParser
import com.ppnam.station2aa.data.local.OfflineQueueDao
import com.ppnam.station2aa.data.session.OperatorSession
import com.ppnam.station2aa.data.session.OperatorSessionHolder
import com.ppnam.station2aa.data.settings.SettingsRepository
import com.ppnam.station2aa.domain.repository.MqttConnectionState
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

data class TestBody(val value: String = "")

class MqttRequestCorrelationTest {

    private lateinit var repo: MqttRepositoryImpl
    private lateinit var sessionHolder: OperatorSessionHolder
    private val published = mutableListOf<Pair<String, ByteArray>>()

    @Before
    fun setup() {
        sessionHolder = OperatorSessionHolder()
        repo = MqttRepositoryImpl(
            clientFactory = mock(),
            settingsRepository = mock<SettingsRepository>(),
            offlineQueueDao = mock<OfflineQueueDao>(),
            sessionHolder = sessionHolder,
        )
        published.clear()
        repo.publishFn = { topic, bytes -> published += topic to bytes }
        forceConnected()
    }

    private fun forceConnected() {
        val field = MqttRepositoryImpl::class.java.getDeclaredField("_connectionState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(repo) as MutableStateFlow<MqttConnectionState>).value = MqttConnectionState.CONNECTED
    }

    private fun messageIdOf(index: Int): String =
        JsonParser.parseString(String(published[index].second)).asJsonObject.get("messageId").asString

    private fun respond(inResponseTo: String, accepted: Boolean = true, value: String = "ok") {
        val json = """
            {"inResponseToMessageId":"$inResponseTo","schemaVersion":"3.0","accepted":$accepted,
             "nextAction":"scan_ingredient","value":"$value"}
        """.trimIndent()
        repo.handleIncomingResponse("PPNAM/handheld_1/res/test_result", json.toByteArray())
    }

    @Test
    fun `two concurrent requests whose responses arrive out of order each resolve correctly`() = runTest {
        val first = async {
            repo.request("a_requested", "test_result", EmptyPayload, null, TestBody::class.java)
        }
        val second = async {
            repo.request("b_requested", "test_result", EmptyPayload, null, TestBody::class.java)
        }

        // Wait until both requests have actually published before answering either.
        while (published.size < 2) yield()

        val firstId = messageIdOf(0)
        val secondId = messageIdOf(1)

        // Answer in REVERSE order. Topic-based matching would hand the second response to the
        // first caller; inResponseToMessageId must not.
        respond(secondId, value = "second")
        respond(firstId, value = "first")

        val firstOutcome = first.await()
        val secondOutcome = second.await()

        assertTrue(firstOutcome is MqttOutcome.Accepted)
        assertTrue(secondOutcome is MqttOutcome.Accepted)
        assertEquals("first", (firstOutcome as MqttOutcome.Accepted).body.value)
        assertEquals("second", (secondOutcome as MqttOutcome.Accepted).body.value)
    }

    @Test
    fun `login and logout in flight together on operator_context resolve correctly`() = runTest {
        // Both request types answer on the SAME response topic. This is why topic matching cannot work.
        val login = async {
            repo.request("login_requested", "operator_context", EmptyPayload, null, TestBody::class.java)
        }
        val logout = async {
            repo.request("reader_logout_requested", "operator_context", EmptyPayload, null, TestBody::class.java)
        }
        while (published.size < 2) yield()

        respond(messageIdOf(1), value = "logout")
        respond(messageIdOf(0), value = "login")

        assertEquals("login", (login.await() as MqttOutcome.Accepted).body.value)
        assertEquals("logout", (logout.await() as MqttOutcome.Accepted).body.value)
    }

    @Test
    fun `request publishes to the req topic`() = runTest {
        val call = async {
            repo.request("login_requested", "operator_context", EmptyPayload, null, TestBody::class.java)
        }
        while (published.isEmpty()) yield()
        assertEquals("PPNAM/handheld_1/req/login_requested", published[0].first)
        respond(messageIdOf(0))
        call.await()
    }

    @Test
    fun `request injects the active operator session id`() = runTest {
        sessionHolder.set(
            OperatorSession(
                operatorSessionId = "session-abc",
                operatorId = "OP-001",
                operatorName = "Operator One",
                role = "Operator",
            )
        )
        val call = async {
            repo.request("active_job_cards_requested", "active_job_cards_list", EmptyPayload, null, TestBody::class.java)
        }
        while (published.isEmpty()) yield()
        val json = JsonParser.parseString(String(published[0].second)).asJsonObject
        assertEquals("session-abc", json.get("operatorSessionId").asString)
        respond(messageIdOf(0))
        call.await()
    }

    @Test
    fun `a rejected response is classified as Rejected and still carries its body`() = runTest {
        val call = async {
            repo.request("a_requested", "test_result", EmptyPayload, null, TestBody::class.java)
        }
        while (published.isEmpty()) yield()
        respond(messageIdOf(0), accepted = false, value = "still-here")

        val outcome = call.await()
        assertTrue(outcome is MqttOutcome.Rejected)
        assertEquals("still-here", (outcome as MqttOutcome.Rejected).body.value)
        assertEquals(NextAction("scan_ingredient"), outcome.nextAction)
    }

    @Test
    fun `a rejected response exposes its error code`() = runTest {
        val call = async {
            repo.request("a_requested", "test_result", EmptyPayload, null, TestBody::class.java)
        }
        while (published.isEmpty()) yield()
        val id = messageIdOf(0)
        repo.handleIncomingResponse(
            "PPNAM/handheld_1/res/test_result",
            """{"inResponseToMessageId":"$id","accepted":false,"errorCode":"session_required","reason":"No session"}""".toByteArray()
        )

        val outcome = call.await() as MqttOutcome.Rejected
        assertEquals(ErrorCode.SESSION_REQUIRED, outcome.errorCode)
        assertEquals("No session", outcome.reason)
    }

    @Test
    fun `request returns NotConnected without publishing when disconnected`() = runTest {
        val field = MqttRepositoryImpl::class.java.getDeclaredField("_connectionState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(repo) as MutableStateFlow<MqttConnectionState>).value = MqttConnectionState.DISCONNECTED

        val outcome = repo.request("a_requested", "test_result", EmptyPayload, null, TestBody::class.java)

        assertEquals(MqttOutcome.NoResponse(FailureKind.NotConnected), outcome)
        assertTrue(published.isEmpty())
    }

    @Test
    fun `an unmatched response is dropped without side effects`() = runTest {
        repo.handleIncomingResponse(
            "PPNAM/handheld_1/res/test_result",
            """{"inResponseToMessageId":"nobody-is-waiting","accepted":true}""".toByteArray()
        )
        // No crash, nothing pending. Reaching here is the assertion.
        assertTrue(published.isEmpty())
    }

    @Test
    fun `a response with no inResponseToMessageId is dropped`() = runTest {
        repo.handleIncomingResponse(
            "PPNAM/handheld_1/res/test_result",
            """{"accepted":true}""".toByteArray()
        )
        assertTrue(published.isEmpty())
    }

    @Test
    fun `a malformed response body yields MalformedResponse`() = runTest {
        val call = async {
            repo.request("a_requested", "test_result", EmptyPayload, null, TestBody::class.java)
        }
        while (published.isEmpty()) yield()
        val id = messageIdOf(0)
        // Valid envelope so it routes, but `value` is an object where a String is expected.
        repo.handleIncomingResponse(
            "PPNAM/handheld_1/res/test_result",
            """{"inResponseToMessageId":"$id","accepted":true,"value":{"nested":1}}""".toByteArray()
        )
        assertEquals(MqttOutcome.NoResponse(FailureKind.MalformedResponse), call.await())
    }
}
