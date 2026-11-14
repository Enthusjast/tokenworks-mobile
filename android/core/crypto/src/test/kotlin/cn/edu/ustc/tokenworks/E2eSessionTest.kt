package cn.edu.ustc.tokenworks

import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class E2eSessionTest {
    private fun direct(bytes: ByteArray = byteArrayOf(), prefix: Int = 0): ByteBuffer =
        ByteBuffer.allocateDirect(prefix + bytes.size).apply { position(prefix); put(bytes); flip(); position(prefix) }

    private fun pair(): Pair<E2eSession, E2eSession> {
        val a = E2eSession.generateStatic()
        val b = E2eSession.generateStatic()
        val initiator = E2eSession.initiator(a.privateKey, b.publicKey, byteArrayOf(1, 2, 3))
        val responder = E2eSession.responder(b.privateKey, byteArrayOf(1, 2, 3))
        a.privateKey.fill(0); b.privateKey.fill(0)
        val wire = ByteBuffer.allocateDirect(65535)
        val clear = ByteBuffer.allocateDirect(65535)
        assertEquals(96, initiator.handshakeWrite(direct(), wire))
        wire.flip(); assertEquals(0, responder.handshakeRead(wire, clear)); wire.clear()
        assertEquals(48, responder.handshakeWrite(direct(), wire))
        wire.flip(); assertEquals(0, initiator.handshakeRead(wire, clear))
        val ai = initiator.finishHandshake(); val bi = responder.finishHandshake()
        assertArrayEquals(ai.handshakeHash, bi.handshakeHash)
        assertArrayEquals(b.publicKey, ai.remoteStatic); assertArrayEquals(a.publicKey, bi.remoteStatic)
        return initiator to responder
    }

    @Test fun positionsSlicesMaximumAndBidirectionalRekey() {
        val (a, b) = pair()
        a.use { b.use {
            val bytes = ByteArray(65519) { (it % 251).toByte() }
            val input = direct(bytes, 7)
            val backing = ByteBuffer.allocateDirect(65535 + 13).apply { position(13) }
            val output = backing.slice()
            assertEquals(65535, a.encrypt(input, output))
            assertEquals(input.limit(), input.position()); assertEquals(65535, output.position())
            output.flip()
            val plainBacking = ByteBuffer.allocateDirect(65535 + 9).apply { position(9) }
            val plain = plainBacking.slice().apply { position(3) }
            assertEquals(65519, b.decrypt(output, plain))
            assertEquals(65522, plain.position()); assertEquals(output.limit(), output.position())
            val decoded = ByteArray(bytes.size)
            plain.flip(); plain.position(3); plain.get(decoded)
            assertArrayEquals(bytes, decoded)
            a.rekeySend(); b.rekeyReceive(); b.rekeySend(); a.rekeyReceive()
            val reverse = ByteBuffer.allocateDirect(64)
            b.encrypt(direct(byteArrayOf(4, 5, 6)), reverse); reverse.flip()
            val result = ByteBuffer.allocateDirect(64)
            assertEquals(3, a.decrypt(reverse, result))
        } }
    }

    @Test fun publicIk1VectorDecodesThroughProductionJni() {
        val host = "4a3acbfdb163dec651dfa3194dece676d437029c62a408b4c5ea9114246e4893".unhex()
        E2eSession.responder(host, "4a6f686e2047616c74".unhex()).use { session ->
            val record = "ca35def5ae56cec33dc2036731ab14896bc4c75dbb07a61f879f8e3afa4c7944718da798efbcd91528520204f904b9bd6c7413dccdc214d951e15253e39987f18146e8cd0873654207148333479d4d16c289f0294b29960a72f48e0b7bba2e89083169825e59642148d492020664ccf7".unhex()
            val output = ByteBuffer.allocateDirect(128).apply { position(11) }
            assertEquals(16, session.handshakeRead(direct(record, 3), output))
            val payload = ByteArray(16); output.flip(); output.position(11); output.get(payload)
            assertArrayEquals("4c756477696720766f6e204d69736573".unhex(), payload)
            val response = ByteBuffer.allocateDirect(128)
            session.handshakeWrite(direct(), response)
            assertEquals(32, session.finishHandshake().handshakeHash.size)
        }
        host.fill(0)
    }

    @Test fun authenticationFailureWipesOnlyOutputRegionAndRetiresState() {
        val (a, b) = pair()
        a.use { b.use {
            val wire = ByteBuffer.allocateDirect(64)
            a.encrypt(direct(byteArrayOf(1, 2)), wire)
            wire.put(wire.position() - 1, (wire.get(wire.position() - 1).toInt() xor 1).toByte()); wire.flip()
            val output = ByteBuffer.allocateDirect(80)
            for (index in 0 until 80) output.put(index, 0x55)
            output.position(9); output.limit(73)
            expectFailure("e2e/auth") { b.decrypt(wire, output) }
            assertEquals(9, output.position()); assertEquals(0, wire.position())
            for (index in 9 until 73) assertEquals(0, output.get(index).toInt())
            output.clear()
            assertEquals(0x55, output.get(8).toInt()); assertEquals(0x55, output.get(73).toInt())
            expectFailure("e2e/closed") { b.rekeyReceive() }
        } }
    }

    @Test fun replayTruncationOversizeOverlapAndClosedAreRefused() {
        for (mode in 0..3) {
            val (a, b) = pair()
            a.use { b.use {
                val wire = ByteBuffer.allocateDirect(65535)
                val output = ByteBuffer.allocateDirect(65535)
                a.encrypt(direct(byteArrayOf(1, 2, 3)), wire); wire.flip()
                when (mode) {
                    0 -> { b.decrypt(wire, output); wire.rewind(); output.clear() }
                    1 -> wire.limit(wire.limit() - 1)
                    2 -> wire.limit(15)
                    3 -> { expectFailure("e2e/overlap") { b.decrypt(wire, wire.duplicate()) }; return@use }
                }
                expectFailure("e2e/") { b.decrypt(wire, output) }
                expectFailure("e2e/closed") { b.rekeyReceive() }
            } }
        }
        val (a, b) = pair()
        a.use { b.use {
            expectFailure("e2e/bounds") { a.encrypt(direct(ByteArray(65520)), ByteBuffer.allocateDirect(65535)) }
            expectFailure("e2e/closed") { a.encrypt(direct(), ByteBuffer.allocateDirect(64)) }
        } }
    }

    @Test fun directBuffersRequiredAndConcurrentCallsSerializeWithoutNonceReuse() {
        val (a, b) = pair()
        a.use { b.use {
            expectFailure("e2e/direct-buffer") { a.encrypt(ByteBuffer.wrap(byteArrayOf(1)), ByteBuffer.allocateDirect(64)) }
            val executor = Executors.newFixedThreadPool(8)
            val records = Collections.synchronizedSet(mutableSetOf<String>())
            try {
                val jobs = (0 until 128).map {
                    executor.submit {
                        val wire = ByteBuffer.allocateDirect(64)
                        a.encrypt(direct(byteArrayOf(7)), wire)
                        wire.flip(); val bytes = ByteArray(wire.remaining()); wire.get(bytes)
                        check(records.add(bytes.hex()))
                    }
                }
                jobs.forEach { it.get() }
                assertEquals(128, records.size)
            } finally { executor.shutdownNow() }
        } }
        a.close(); b.close()
        expectFailure("e2e/closed") { a.rekeySend() }
    }

    private fun expectFailure(code: String, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (failure: Exception) {
            assertTrue("Expected stable error $code", failure.message?.startsWith(code) == true)
        }
    }
}

internal fun String.unhex(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
