package cn.edu.ustc.tokenworks

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.Buffer
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class E2eSessionInstrumentationTest {
    private fun position(buffer: ByteBuffer, value: Int) { (buffer as Buffer).position(value) }
    private fun flip(buffer: ByteBuffer) { (buffer as Buffer).flip() }
    private fun clear(buffer: ByteBuffer) { (buffer as Buffer).clear() }
    private fun direct(bytes: ByteArray = byteArrayOf(), prefix: Int = 0): ByteBuffer =
        ByteBuffer.allocateDirect(bytes.size + prefix).apply {
            position(this, prefix); put(bytes); flip(this); position(this, prefix)
        }
    private fun unhex(value: String): ByteArray = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun packagedProductionEngineDecodesPublicIk1() {
        val key = unhex("4a3acbfdb163dec651dfa3194dece676d437029c62a408b4c5ea9114246e4893")
        E2eSession.responder(key, unhex("4a6f686e2047616c74")).use { session ->
            key.fill(0)
            val record = unhex("ca35def5ae56cec33dc2036731ab14896bc4c75dbb07a61f879f8e3afa4c7944718da798efbcd91528520204f904b9bd6c7413dccdc214d951e15253e39987f18146e8cd0873654207148333479d4d16c289f0294b29960a72f48e0b7bba2e89083169825e59642148d492020664ccf7")
            val output = ByteBuffer.allocateDirect(256)
            position(output, 7)
            assertEquals(16, session.handshakeRead(direct(record, 3), output))
            val decoded = ByteArray(16)
            flip(output); position(output, 7); output.get(decoded)
            assertArrayEquals(unhex("4c756477696720766f6e204d69736573"), decoded)
            clear(output); session.handshakeWrite(direct(), output)
            assertEquals(32, session.finishHandshake().handshakeHash.size)
        }
    }

    @Test fun productionRandomHandshakeMaximumRekeyAuthenticationAndClosed() {
        val a = E2eSession.generateStatic(); val b = E2eSession.generateStatic()
        val prologue = "android-production-noise-proof".toByteArray()
        E2eSession.initiator(a.privateKey, b.publicKey, prologue).use { initiator ->
            E2eSession.responder(b.privateKey, prologue).use { responder ->
                a.privateKey.fill(0); b.privateKey.fill(0)
                val wire = ByteBuffer.allocateDirect(65535)
                val plain = ByteBuffer.allocateDirect(65535)
                assertEquals(96, initiator.handshakeWrite(direct(), wire))
                flip(wire); assertEquals(0, responder.handshakeRead(wire, plain)); clear(wire)
                assertEquals(48, responder.handshakeWrite(direct(), wire))
                flip(wire); assertEquals(0, initiator.handshakeRead(wire, plain)); clear(wire)
                val ai = initiator.finishHandshake(); val bi = responder.finishHandshake()
                assertArrayEquals(ai.handshakeHash, bi.handshakeHash)
                assertArrayEquals(a.publicKey, bi.remoteStatic); assertArrayEquals(b.publicKey, ai.remoteStatic)
                val payload = ByteArray(65519) { (it % 251).toByte() }
                val input = direct(payload, 5)
                assertEquals(65535, initiator.encrypt(input, wire))
                assertEquals(input.limit(), input.position())
                flip(wire); assertEquals(65519, responder.decrypt(wire, plain))
                flip(plain); val decoded = ByteArray(65519); plain.get(decoded); assertArrayEquals(payload, decoded)
                clear(wire); clear(plain)
                initiator.rekeySend(); responder.rekeyReceive()
                responder.rekeySend(); initiator.rekeyReceive()
                responder.encrypt(direct(byteArrayOf(1, 2, 3)), wire)
                flip(wire); assertEquals(3, initiator.decrypt(wire, plain)); clear(wire); clear(plain)
                initiator.encrypt(direct(byteArrayOf(9, 8, 7)), wire)
                wire.put(wire.position() - 1, (wire.get(wire.position() - 1).toInt() xor 1).toByte())
                flip(wire)
                for (index in 0 until plain.capacity()) plain.put(index, 0x55)
                try { responder.decrypt(wire, plain); fail("Expected authentication failure") }
                catch (expected: IllegalStateException) { assertEquals("e2e/auth", expected.message) }
                for (index in 0 until plain.capacity()) assertEquals(0, plain.get(index).toInt())
                try { responder.rekeyReceive(); fail("Closed engine reused") }
                catch (expected: IllegalStateException) { assertEquals("e2e/closed", expected.message) }
                responder.close(); responder.close()
            }
        }
        Log.i("NoiseEngineProof", "${E2eSession.SUITE}: SDK=${Build.VERSION.SDK_INT}, ABI=${Build.SUPPORTED_ABIS.first()}, production random/native JNI passed")
    }
}
