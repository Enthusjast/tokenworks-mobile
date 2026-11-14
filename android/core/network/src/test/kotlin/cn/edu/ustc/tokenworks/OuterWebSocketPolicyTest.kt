package cn.edu.ustc.tokenworks

import org.apache.hc.core5.http.HttpVersion
import org.apache.hc.core5.http.impl.io.DefaultHttpResponseWriter
import org.apache.hc.core5.http.impl.io.SessionOutputBufferImpl
import org.apache.hc.core5.http.message.BasicClassicHttpResponse
import org.java_websocket.WebSocketImpl
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.enums.Role
import org.java_websocket.exceptions.IncompleteHandshakeException
import org.java_websocket.exceptions.InvalidDataException
import org.java_websocket.framing.BinaryFrame
import org.java_websocket.framing.ContinuousFrame
import org.java_websocket.framing.TextFrame
import org.java_websocket.handshake.ClientHandshakeBuilder
import org.java_websocket.handshake.HandshakeImpl1Client
import org.java_websocket.handshake.ServerHandshake
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer

class OuterWebSocketPolicyTest {
    private fun state(failed: (IOException) -> Unit = {}): WssConnectionState = WssConnectionState(CarrierReceiver({ _, _, _ -> }, { _ -> }, failed))
    private fun websocket(): WebSocketClient = object : WebSocketClient(URI("ws://unused.invalid")) {
        override fun onOpen(handshake: ServerHandshake) {}
        override fun onMessage(message: String) {}
        override fun onClose(code: Int, reason: String, remote: Boolean) {}
        override fun onError(error: Exception) {}
    }
    private fun response(status: Int, nonce: Boolean = false): ByteArray {
        val response = BasicClassicHttpResponse(status)
        response.version = HttpVersion.HTTP_1_1
        if (nonce) {
            response.setHeader("DPoP-Nonce", "nonce-from-real-header")
            response.setHeader("WWW-Authenticate", "DPoP error=\"use_dpop_nonce\"")
        }
        val output = ByteArrayOutputStream()
        val buffer = SessionOutputBufferImpl(1024)
        DefaultHttpResponseWriter().write(response, buffer, output); buffer.flush(output)
        return output.toByteArray()
    }
    @Test fun publicHttpParserAcceptsAStatusLineAndExposesOnlyCompleteNonceChallenges() {
        var rejected: IOException? = null
        val state = state { rejected = it }
        try {
            val draft = BoundedOuterDraft(state)
            val bytes = response(401, nonce = true)
            val partial = ByteBuffer.wrap(bytes.copyOf(bytes.size - 1))
            assertThrows(IncompleteHandshakeException::class.java) { draft.translateHandshake(partial) }
            assertEquals(0, partial.position())
            assertNull(rejected)
            val decoded = draft.translateHandshake(ByteBuffer.wrap(bytes)) as ServerHandshake
            assertEquals(401, decoded.httpStatus.toInt())
            assertThrows(InvalidDataException::class.java) { draft.acceptHandshakeAsClient(HandshakeImpl1Client(), decoded) }
            val refusal = rejected as CarrierUpgradeException
            assertEquals(401, refusal.status)
            assertTrue(refusal.nonceChallenge)
            assertEquals("nonce-from-real-header", refusal.nonce)
        } finally { state.close() }
    }
    @Test fun copiedDraftRejectsNonFinalAndContinuousFramesBeforeAnyFragmentAccumulation() {
        val frames = listOf(BinaryFrame().apply { setFin(false) }, ContinuousFrame(), TextFrame(), BinaryFrame().apply { setRSV1(true) },
            BinaryFrame().apply { setTransferemasked(true) })
        for (frame in frames) {
            val state = state()
            try {
                val draft = BoundedOuterDraft(state).copyInstance()
                val socket = websocket().connection as WebSocketImpl
                assertThrows(InvalidDataException::class.java) { draft.processFrame(socket, frame) }
            } finally { state.close() }
        }
    }
    @Test fun standardFrameDecoderRejects65538BeforeAllocatingAnOuterPayload() {
        val state = state()
        try {
            val server = Draft_6455().apply { setParseMode(Role.SERVER) }
            val encoded = server.createBinaryFrame(BinaryFrame().apply { setPayload(ByteBuffer.wrap(ByteArray(65538))) })
            val client = BoundedOuterDraft(state).apply { setParseMode(Role.CLIENT) }
            assertThrows(InvalidDataException::class.java) { client.translateFrame(encoded) }
        } finally { state.close() }
    }
}
