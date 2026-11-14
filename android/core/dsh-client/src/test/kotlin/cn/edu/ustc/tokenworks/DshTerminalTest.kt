package cn.edu.ustc.tokenworks

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class DshTerminalTest {
    private fun info(state: String = "running", id: String = "term-1") = jsonObject(
        "id" to id.json(), "title" to "shell".json(),
        "shell" to jsonObject("path" to "/bin/bash".json(), "args" to JsonArray(emptyList()), "name" to "bash".json()),
        "cwd" to "/home/me".json(), "cols" to 80L.json(), "rows" to 24L.json(), "state" to state.json(), "exitCode" to JsonNull,
    )

    @Test fun snapshotAndStateFramesUpdateInfoAndSequence() {
        val snapshot = DshTerminal.decode(jsonObject(
            "type" to "snapshot".json(), "sequence" to 7L.json(), "screen" to "$ ".json(),
            "info" to info(), "writable" to JsonBoolean(true),
        ))
        assertTrue(snapshot is TerminalFrame.Snapshot)
        assertEquals("$ ", (snapshot as TerminalFrame.Snapshot).screen)
        assertTrue(snapshot.writable)
        assertEquals("/bin/bash", snapshot.info.shellPath)
        assertEquals(80, snapshot.info.cols)

        val state = DshTerminal.decode(jsonObject("type" to "state".json(), "writable" to JsonBoolean(false), "info" to info("exited")))
        assertTrue(state is TerminalFrame.State)
        assertEquals("exited", (state as TerminalFrame.State).info.state)
    }

    @Test fun outputFramesCarryChunksInOrder() {
        val frame = DshTerminal.decode(jsonObject("type" to "output".json(), "sequence" to 12L.json(), "data" to "ls\n".json()))
        assertEquals("ls\n", (frame as TerminalFrame.Output).data)
        assertEquals(12L, frame.sequence)
    }

    @Test fun unknownFrameKindsAreProtocolErrors() {
        assertThrows(ProtocolException::class.java) {
            DshTerminal.decode(jsonObject("type" to "mystery".json(), "sequence" to 1L.json()))
        }
    }

    @Test fun inputIsBoundedUntilNativeAckAndIsNeverReplayedAfterDetach(): Unit = runBlocking {
        val sent = mutableListOf<JsonObject>()
        val mux = DshMux({ sent += JsonDocument.parse(it).root; true }, { _, _ -> })
        val stream = mux.open("terminal/attach", jsonObject("args" to jsonObject()))
        val terminal = DshTerminal(stream, DshTerminal.decodeInfo(info()), TerminalEnvironment("/home/me", 4, 4096, 80, 24, 100))
        try {
            assertFalse(terminal.write("x"))
            mux.event(jsonObject("type" to "snapshot".json(), "sequence" to 0L.json(), "screen" to "$ ".json(),
                "info" to info(), "writable" to JsonBoolean(true),
                "inputWindow" to jsonObject("maxBytes" to 512L.json(), "frameOverheadBytes" to 0L.json())))
            terminal.receive()
            assertFalse(terminal.resize(81, 24))
            assertTrue(terminal.write("🙂"))
            assertFalse(terminal.write("x"))
            assertEquals(1, sent.count { it.fields["type"] == "item".json() })
            mux.event(jsonObject("type" to "ack".json(), "sequence" to 1L.json()))
            terminal.receive()
            assertTrue(terminal.write("x"))
            assertEquals(2, sent.count { it.fields["type"] == "item".json() })
            terminal.close()
            assertFalse(terminal.write("x"))
            assertEquals(2, sent.count { it.fields["type"] == "item".json() })
        } finally { terminal.close(); mux.close() }
    }
}
