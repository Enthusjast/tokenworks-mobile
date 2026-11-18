package cn.edu.ustc.tokenworks

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class TerminalWatcherTest {
    @Test fun independentTabsReceiveOutputAndInputTargetsOnlySelection(): Unit = runBlocking {
        val host = TerminalHost()
        TerminalWatcher(host, "scoped-session", this).use { watcher ->
            watcher.open("first")
            val first = host.connection("first")
            first.snapshot("first \u001b[?1h")
            watcher.await { it.writable }
            watcher.open("second")
            val second = host.connection("second")
            second.snapshot("second \u001b[?2004h")
            watcher.await { it.selectedId == "second" && it.writable }
            assertEquals(listOf("first", "second"), watcher.state.value.terminals.map { it.id })
            assertTrue(first.writes.isEmpty())
            assertTrue(second.writes.isEmpty())

            first.output(1, "background")
            watcher.await { it.tabs["first"]?.display?.text == "first background" }
            assertEquals("second", watcher.state.value.screen.trimEnd())
            assertFalse(watcher.sendKey("first", TerminalKey.UP))
            assertTrue(watcher.paste("second", "safe\n\u001bvalue"))
            assertEquals("\u001b[200~safe\nvalue\u001b[201~", second.writes.single().string("data"))

            watcher.select("first")
            assertEquals("first background", watcher.state.value.screen)
            assertTrue(watcher.sendKey("first", TerminalKey.UP))
            assertEquals("\u001bOA", first.writes.first().string("data"))
            assertTrue(watcher.resize(100, 28))
            assertEquals(80, watcher.state.value.display!!.cols)
            assertEquals("resize", first.writes.last().string("type"))
            first.state(100, 28)
            watcher.await { it.display?.cols == 100 && it.display?.rows == 28 }
            assertEquals(80, watcher.state.value.tabs.getValue("second").display!!.cols)

            watcher.detach()
            assertNull(watcher.state.value.attached)
            assertTrue(first.mux.closed())
            assertFalse(second.mux.closed())
            assertFalse(host.calls.contains("terminal/close"))
            watcher.attach("first")
            val reopened = host.connection("first")
            reopened.snapshot("restored")
            watcher.await { it.writable && it.screen == "restored" }
            assertTrue(reopened.writes.isEmpty())
            watcher.closeTerminal("second")
            assertEquals(listOf("first"), watcher.state.value.terminals.map { it.id })
            assertEquals("first", watcher.state.value.selectedId)
            assertTrue(second.mux.closed())
            assertFalse(reopened.mux.closed())
        }
        assertEquals(1, host.calls.count { it == "terminal/close" })
    }

    @Test fun sequenceGapRecoversFromBaselineWithoutReplayingInput(): Unit = runBlocking {
        val host = TerminalHost()
        TerminalWatcher(host, "scoped-session", this).use { watcher ->
            watcher.open("first")
            val first = host.connection("first")
            first.snapshot("first")
            watcher.await { it.writable }
            assertTrue(watcher.submit("first", "user text"))
            first.output(2, "discard")
            watcher.await { it.reconnecting }
            assertFalse(watcher.send("not queued"))
            val replacement = host.connection("first")
            replacement.snapshot("new baseline", 2)
            replacement.output(3, " ok")
            watcher.await { it.writable && it.screen == "new baseline ok" }
            assertTrue(replacement.writes.isEmpty())
        }
        assertEquals(1, host.calls.count { it == "terminal/create" })
        assertFalse(host.calls.contains("terminal/close"))
    }

    private suspend fun TerminalWatcher.await(predicate: (TerminalUiState) -> Boolean) = withTimeout(5_000) { state.first(predicate) }

    private class TerminalHost : DshTransport {
        override val runtimeId = "terminal-fixture"
        override val state = MutableStateFlow(DshTransportState(DshTransportPath.LAN, DshTransportPhase.READY))
        val calls = mutableListOf<String>()
        private val terminals = linkedMapOf<String, JsonObject>()
        private val opened = Channel<Connection>(Channel.UNLIMITED)

        override suspend fun native(endpoint: String, args: JsonObject): JsonValue {
            calls.add(endpoint)
            assertEquals("scoped-session", args.string(if (endpoint == "terminal/list") "sessionId" else "agentId"))
            return when (endpoint) {
                "terminal/list" -> JsonArray(terminals.values.toList())
                "terminal/create" -> args.obj("request").let { request ->
                    val id = request.string("id")
                    info(id).also { terminals[id] = it }
                }
                "terminal/environment" -> jsonObject("cwd" to "/test".json(), "maxInputBytes" to 1024L.json(),
                    "maxBufferedBytes" to 4096L.json(), "maxCols" to 120L.json(), "maxRows" to 40L.json(), "scrollback" to 20L.json())
                "terminal/close" -> { terminals.remove(args.string("id")); JsonNull }
                else -> error(endpoint)
            }
        }
        override suspend fun openMux(): DshMux {
            val connection = Connection()
            connection.mux = DshMux({ text ->
                val message = JsonDocument.parse(text).root
                when (message.string("type")) {
                    "open" -> {
                        connection.id = message.obj("payload").obj("args").string("id")
                        assertTrue(opened.trySend(connection).isSuccess)
                    }
                    "item" -> connection.writes.add(message.obj("value"))
                }
                true
            }, { _, _ -> })
            return connection.mux
        }
        suspend fun connection(id: String): Connection = withTimeout(5_000) {
            opened.receive().also { assertEquals(id, it.id) }
        }
        override suspend fun download(path: String, sink: OutputStream, maxBytes: Long, range: DshByteRange?) = error("unused")
        override suspend fun upload(path: String, mediaType: String, source: InputStream, sizeBytes: Long) = error("unused")
        override fun close() { opened.close() }
    }

    private class Connection {
        lateinit var id: String
        lateinit var mux: DshMux
        val writes = CopyOnWriteArrayList<JsonObject>()
        fun snapshot(screen: String, sequence: Long = 0) = mux.event(jsonObject("type" to "snapshot".json(),
            "sequence" to sequence.json(), "screen" to screen.json(), "info" to info(id), "writable" to JsonBoolean(true)))
        fun output(sequence: Long, data: String) = mux.event(jsonObject("type" to "output".json(),
            "sequence" to sequence.json(), "data" to data.json()))
        fun state(cols: Int, rows: Int) = mux.event(jsonObject("type" to "state".json(),
            "info" to info(id, cols, rows), "writable" to JsonBoolean(true)))
    }

    private companion object {
        fun info(id: String, cols: Int = 80, rows: Int = 24) = jsonObject(
            "id" to id.json(), "title" to id.json(),
            "shell" to jsonObject("path" to "/bin/bash".json(), "args" to JsonArray(emptyList()), "name" to "bash".json()),
            "cwd" to "/test".json(), "cols" to cols.toLong().json(), "rows" to rows.toLong().json(),
            "state" to "running".json(), "exitCode" to JsonNull,
        )
    }
}
