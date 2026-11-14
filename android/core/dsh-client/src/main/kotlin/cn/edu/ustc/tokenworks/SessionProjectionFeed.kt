package cn.edu.ustc.tokenworks

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Finished Host projection values only; this client does not fold domain events. */
class SessionProjectionFeed(
    private val transport: DshTransport,
    owner: CoroutineScope,
    private val userQuestions: (sessionId: String, value: JsonObject) -> Unit,
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
                            DshMethods.SessionControl.output.decode(value)
                            val root = value as JsonObject
                            when (root.string("type")) {
                                "baseline" -> {
                                    if (baselineSeen) throw ProtocolException()
                                    baselineSeen = true
                                    root.obj("value").obj("projections").fields.forEach { (sessionId, raw) ->
                                        val projection = raw as JsonObject
                                        val cut = projection.integer("asOfSeq", -1)
                                        val questions = projection.obj("values").fields["userQuestions"] as? JsonObject
                                        if (questions != null) { watermarks[sessionId] = cut; userQuestions(sessionId, questions) }
                                    }
                                }
                                "projection" -> {
                                    if (!baselineSeen) throw ProtocolException()
                                    if (root.string("key") == "userQuestions") {
                                        val sessionId = root.string("sessionId")
                                        val seq = root.integer("seq", -1)
                                        if (seq > (watermarks[sessionId] ?: Long.MIN_VALUE)) {
                                            watermarks[sessionId] = seq
                                            userQuestions(sessionId, root.obj("value"))
                                        }
                                    }
                                }
                                else -> throw ProtocolException()
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
    override fun close() { scope.cancel() }
}
