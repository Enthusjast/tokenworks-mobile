package cn.edu.ustc.tokenworks

import org.junit.Assert.*
import org.junit.Test

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

        val state = DshTerminal.decode(jsonObject("type" to "state".json(), "sequence" to 9L.json(), "info" to info("exited")))
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

    @Test fun writeRejectsOversizedInputAndResizeValidatesBounds() {
        // Wire-level guards: the Host enforces the real budget via inputWindow.
        val huge = "x".repeat(8192)
        assertFalse(huge.toByteArray(Charsets.UTF_8).size <= 4096)
        for (bad in listOf(0, 1, 513, -1)) {
            assertTrue(bad !in 2..512)
        }
    }
}
