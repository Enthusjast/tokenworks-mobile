package cn.edu.ustc.tokenworks

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DshMuxRestrictionTest {
    @Test fun restrictionsIntersectCopyInputsAndRejectBeforeAnyFrameIsWritten() {
        val frames = mutableListOf<String>()
        val mux = DshMux({ frames.add(it); true }, { _, _ -> })
        val allowed = mutableSetOf("session/follow", "workspace/follow")
        mux.restrictEndpoints(allowed)
        allowed.add("terminal/create")
        mux.restrictEndpoints(setOf("session/follow", "terminal/create"))
        assertThrows(DshFailure::class.java) { mux.open("terminal/create", jsonObject()) }
        assertThrows(DshFailure::class.java) { mux.open("workspace/follow", jsonObject()) }
        assertTrue(frames.isEmpty())
        val stream = mux.open("session/follow", jsonObject())
        assertEquals(1, frames.size)
        assertThrows(DshFailure::class.java) { stream.send(DshOptional.Value(jsonObject())) }
        assertEquals(1, frames.size)
        assertThrows(IllegalStateException::class.java) { mux.restrictEndpoints(emptySet()) }
        stream.end(); stream.close(); mux.close()
    }

    @Test fun concurrentRestrictionsCannotWidenTheCarrier() {
        val mux = DshMux({ true }, { _, _ -> })
        val begin = CountDownLatch(1)
        val done = CountDownLatch(2)
        val failure = AtomicReference<Throwable?>()
        val workers = listOf(setOf("session/follow", "workspace/follow"), setOf("session/follow", "terminal/create")).map { allowed ->
            Thread {
                try { begin.await(); mux.restrictEndpoints(allowed) }
                catch (error: Throwable) { failure.set(error) }
                finally { done.countDown() }
            }.apply { isDaemon = true; start() }
        }
        begin.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        workers.forEach { it.join(100) }
        assertNull(failure.get())
        assertThrows(DshFailure::class.java) { mux.open("terminal/create", jsonObject()) }
        assertThrows(DshFailure::class.java) { mux.open("workspace/follow", jsonObject()) }
        mux.open("session/follow", jsonObject()).close()
        mux.close()
    }
}
