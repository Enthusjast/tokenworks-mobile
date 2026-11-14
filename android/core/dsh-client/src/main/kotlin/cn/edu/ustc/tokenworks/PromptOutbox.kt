package cn.edu.ustc.tokenworks

import java.util.Collections

/** Immutable delivery identity; ownerId must include the authenticated account/deployment scope. */
data class DshCommandRoute(val ownerId: String, val hostId: String, val runtimeId: String, val sessionId: String) {
    init {
        Contract.boundedText(ownerId, 512)
        Contract.boundedText(hostId, 128)
        Contract.boundedText(runtimeId, 128)
        Contract.boundedText(sessionId, 128)
    }
}

enum class PromptDeliveryState { PENDING, SENDING, OUTCOME_UNKNOWN, REFUSED }

class PromptOutboxEntry(
    val requestId: String,
    val route: DshCommandRoute,
    val text: String,
    val mode: String,
    val state: PromptDeliveryState = PromptDeliveryState.PENDING,
    receipts: List<String> = emptyList(),
    images: List<NativeImagePart> = emptyList(),
) {
    val receipts: List<String> = Collections.unmodifiableList(ArrayList(receipts))
    val images: List<NativeImagePart> = Collections.unmodifiableList(ArrayList(images))
    val sessionId: String get() = route.sessionId
    val payloadBytes: Int = validateNativePrompt(text, this.receipts, this.images)
    val imageBytes: Int = this.images.sumOf { it.encodedBytes }

    init {
        Contract.uuid(requestId)
        require(mode == "queue" || mode == "steer") { "prompt mode invalid" }
    }

    fun copy(
        requestId: String = this.requestId,
        route: DshCommandRoute = this.route,
        text: String = this.text,
        mode: String = this.mode,
        state: PromptDeliveryState = this.state,
        receipts: List<String> = this.receipts,
        images: List<NativeImagePart> = this.images,
    ) = PromptOutboxEntry(requestId, route, text, mode, state, receipts, images)

    override fun equals(other: Any?): Boolean = other is PromptOutboxEntry && requestId == other.requestId &&
        route == other.route && text == other.text && mode == other.mode && state == other.state &&
        receipts == other.receipts && images == other.images

    override fun hashCode(): Int {
        var result = requestId.hashCode()
        result = 31 * result + route.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + mode.hashCode()
        result = 31 * result + state.hashCode()
        result = 31 * result + receipts.hashCode()
        return 31 * result + images.hashCode()
    }

    override fun toString(): String = "PromptOutboxEntry(requestId=$requestId, state=$state, imageCount=${images.size})"
}

/** Route-scoped view; retry is always an explicit request ID, never a text lookup. */
class PromptOutbox internal constructor(private val commands: DshCommandOutbox, val route: DshCommandRoute) {
    suspend fun prepare(text: String, mode: String = "queue", images: List<NativeImagePart> = emptyList()) =
        commands.prepare(route, text, mode, images = images)
    suspend fun retry(requestId: String) = commands.retry(requestId, route)
    suspend fun markSending(requestId: String) = commands.markSending(requestId, route)
    suspend fun markUnknown(requestId: String) = commands.markUnknown(requestId, route)
    suspend fun markRefused(requestId: String) = commands.markRefused(requestId, route)
    suspend fun settle(requestId: String) = commands.settle(requestId, route)
    fun pending() = commands.pending(route)
}
