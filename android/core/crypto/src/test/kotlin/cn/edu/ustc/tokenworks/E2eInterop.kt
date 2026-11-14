package cn.edu.ustc.tokenworks

import java.io.File
import java.nio.ByteBuffer

/** Test-only subprocess peer; both bindings use OS randomness from the release engine. */
object E2eInterop {
    @JvmStatic fun main(args: Array<String>) {
        val hashes = mutableSetOf<String>()
        for (jvmInitiator in listOf(true, false)) {
            for (failureMode in listOf("tag", "replay", "truncation", "maxlength")) {
                check(hashes.add(exchange(jvmInitiator, failureMode)))
            }
        }
        println("Production Node24 NAPI8 ↔ JVM17 JNI: both roles, public IK1, raw32 bindings, random handshakes, maximum record, bidirectional rekey, tag/replay/truncation/closed passed")
    }

    private fun exchange(jvmInitiator: Boolean, failureMode: String): String {
        val repository = File(System.getProperty("noise.repository"))
        val node = System.getenv("NODE_BINARY") ?: "node"
        val process = ProcessBuilder(node, "--import", "tsx", "tools/native/noise-interop.mjs",
            if (jvmInitiator) "responder" else "initiator")
            .directory(repository).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val reader = process.inputStream.bufferedReader()
        val writer = process.outputStream.bufferedWriter()
        fun send(command: String, value: String = "") { writer.write("$command $value\n"); writer.flush() }
        fun receive(expected: String): String {
            val line = reader.readLine() ?: error("test/peer-eof")
            check(line.length <= 132096 && line.substringBefore(' ') == expected) { "test/peer-response" }
            return line.substringAfter(' ', "")
        }
        val nodePublic = receive("KEY").unhex()
        val keys = E2eSession.generateStatic()
        send("PEER", keys.publicKey.hex())
        val prologue = "real-production-interop-v1".toByteArray(Charsets.UTF_8)
        val session = if (jvmInitiator) E2eSession.initiator(keys.privateKey, nodePublic, prologue)
            else E2eSession.responder(keys.privateKey, prologue)
        keys.privateKey.fill(0)
        val output = ByteBuffer.allocateDirect(65535)
        val plain = ByteBuffer.allocateDirect(65535)
        fun input(bytes: ByteArray) = ByteBuffer.allocateDirect(bytes.size + 3).apply {
            position(3); put(bytes); flip(); position(3)
        }
        fun written(buffer: ByteBuffer): ByteArray {
            buffer.flip()
            return ByteArray(buffer.remaining()).also { buffer.get(it); buffer.clear() }
        }
        fun encrypt(bytes: ByteArray): ByteArray { session.encrypt(input(bytes), output); return written(output) }
        fun decrypt(bytes: ByteArray): ByteArray { session.decrypt(input(bytes), plain); return written(plain) }
        try {
            if (jvmInitiator) {
                session.handshakeWrite(input(byteArrayOf()), output)
                send("IK", written(output).hex())
                session.handshakeRead(input(receive("IK").unhex()), plain)
            } else {
                session.handshakeRead(input(receive("IK").unhex()), plain)
                session.handshakeWrite(input(byteArrayOf()), output)
                send("IK", written(output).hex())
            }
            val binding = session.finishHandshake()
            check(binding.remoteStatic.contentEquals(nodePublic))
            val nodeBinding = receive("BIND").split(' ')
            check(nodeBinding[0] == keys.publicKey.hex() && nodeBinding[1] == binding.handshakeHash.hex())
            for (size in listOf(0, 1, 65519)) {
                val payload = ByteArray(size) { (it % 251).toByte() }
                val ciphertext = encrypt(payload)
                check(ciphertext.size == size + 16)
                send("DECRYPT", ciphertext.hex())
                check(receive("DATA").unhex().contentEquals(payload))
                send("ENCRYPT", payload.hex())
                check(decrypt(receive("DATA").unhex()).contentEquals(payload))
            }
            session.rekeySend(); send("REKEY_RECEIVE"); receive("OK")
            session.rekeyReceive(); send("REKEY_SEND"); receive("OK")
            val payload = "after-bidirectional-rekey".toByteArray()
            val ciphertext = encrypt(payload)
            send("DECRYPT", ciphertext.hex()); check(receive("DATA").unhex().contentEquals(payload))
            send("ENCRYPT", payload.hex()); check(decrypt(receive("DATA").unhex()).contentEquals(payload))
            val damaged = when (failureMode) {
                "replay" -> ciphertext
                "tag" -> encrypt(payload).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
                "truncation" -> encrypt(payload).dropLast(1).toByteArray()
                else -> ByteArray(65536)
            }
            send("DECRYPT", damaged.hex()); check(receive("ERROR").startsWith("e2e/"))
            send("ENCRYPT", "01"); check(receive("ERROR") == "e2e/closed")
            send("CLOSE"); receive("OK"); send("CLOSE"); receive("OK")
            session.close(); session.close()
            try { session.rekeySend(); error("test/closed-accepted") } catch (expected: IllegalStateException) {
                check(expected.message == "e2e/closed")
            }
            writer.close()
            check(process.waitFor() == 0)
            return binding.handshakeHash.hex()
        } finally {
            session.close(); process.destroyForcibly()
        }
    }
}
