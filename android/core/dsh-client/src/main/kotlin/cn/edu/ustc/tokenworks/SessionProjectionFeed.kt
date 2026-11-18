package cn.edu.ustc.tokenworks

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Finished Host projection values only; this client does not fold domain events. */
class SessionProjectionFeed(
    private val transport: DshTransport,
    owner: CoroutineScope,
    private val tokenUsage: ((sessionId: String, snapshot: UsageSnapshot) -> Unit)? = null,
    private val userQuestions: ((sessionId: String, value: JsonObject) -> Unit)? = null,
) : AutoCloseable {
    private val scope = CoroutineScope(owner.coroutineContext + SupervisorJob(owner.coroutineContext[Job]))
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()
    init { scope.launch { follow() } }

    private suspend fun follow() {
        var attempt = 0
        while (scope.isActive) {
            var mux: DshMux? = null
            var stream: DshStream? = null
            try {
                mux = transport.openMux()
                stream = mux.open("session/control", jsonObject("args" to jsonObject()))
                val watermarks = mutableMapOf<String, Long>()
                var baselineSeen = false
                while (scope.isActive) {
                    when (val frame = stream.next() ?: throw DshFailure("session.stream_ended")) {
                        is DshMuxFrame.Item -> {
                            val value = (frame.value as? DshOptional.Value)?.value ?: throw ProtocolException()
                            val frameValue = DshMethods.SessionControl.output.decode(value)
                            when (frameValue) {
                                is DshSessionControlResult.Case1 -> {
                                    if (baselineSeen) throw ProtocolException()
                                    baselineSeen = true
                                    val root = value as JsonObject
                                    root.obj("value").obj("projections").fields.forEach { (sessionId, raw) ->
                                        val projection = raw as JsonObject
                                        val cut = projection.integer("asOfSeq", -1)
                                        watermarks[sessionId] = cut
                                        val values = projection.obj("values")
                                        deliver(sessionId, cut, "userQuestions", values.fields["userQuestions"])
                                        deliver(sessionId, cut, "tokenUsage", values.fields["tokenUsage"])
                                    }
                                }
                                is DshSessionControlResult.Case2 -> {
                                    if (!baselineSeen) throw ProtocolException()
                                    val event = frameValue.value
                                    val seq = (value as JsonObject).integer("seq", 0)
                                    if (seq > (watermarks[event.sessionId] ?: -1L)) {
                                        watermarks[event.sessionId] = seq
                                        for (change in event.changes) deliver(event.sessionId, seq, change.key, change.`val`)
                                    }
                                }
                            }
                            attempt = 0
                            mutableError.value = null
                        }
                        is DshMuxFrame.Error -> throw DshFailure(frame.error.code)
                        is DshMuxFrame.End -> throw DshFailure("session.stream_ended")
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val decision = DshRecoveryPolicy.classify(failure)
                mutableError.value = decision.code
                if (decision.action != DshRecoveryAction.BACKOFF) return
                delay(DshRecoveryPolicy.backoffMillis(attempt++))
            } finally { stream?.close(); mux?.close() }
        }
    }

    private fun deliver(sessionId: String, seq: Long, key: String, value: JsonValue?) {
        val obj = value as? JsonObject ?: return
        when (key) {
            "userQuestions" -> userQuestions?.invoke(sessionId, obj)
            "tokenUsage" -> if (tokenUsage != null) {
                UsageCounts.fromProjection(obj)?.let { tokenUsage.invoke(sessionId, UsageSnapshot(seq, it)) }
            }
        }
    }
    override fun close() { scope.cancel() }
}
