package cn.edu.ustc.tokenworks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.core.os.ConfigurationCompat
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlinx.coroutines.CancellationException

@Composable
internal fun rememberNextChatModel(transport: DshTransport?, sessionId: String?, revision: Long): UsageModel? {
    var next by remember(transport, sessionId) { mutableStateOf<UsageModel?>(null) }
    LaunchedEffect(transport, sessionId, revision) {
        if (transport == null || sessionId == null) return@LaunchedEffect
        next = try {
            val method = DshMethods.SessionProjections
            val args = method.input.encode(DshSessionProjectionsInput(DshSessionProjectionsInputRequest(sessionId))) as JsonObject
            val snapshot = method.output.decode(transport.native(method.endpoint, args)) as? DshSessionProjectionsResult.Case2
            val model = (snapshot?.value?.values?.modelSelection as? DshOptional.Value)?.value?.next
            model?.takeIf { it.provider.isNotBlank() && it.provider.length <= 128 && it.model.isNotBlank() &&
                it.model.length <= 256 && (it.provider + it.model).none(Char::isISOControl) }?.let { UsageModel(it.provider, it.model) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
    return next
}

/** A message gets only its own native measurement, never a session delta or inferred token estimate. */
internal fun transcriptMessageUsage(window: SessionTranscript?): Map<Long, UsageCounts> = buildMap {
    if (window == null) return@buildMap
    for (record in window.records) {
        if (record.seq > window.usageThrough) break
        val sample = record.usage as? SessionUsageEvent.Sample ?: continue
        if (record.type == "assistant/message") put(record.seq, sample.counts)
    }
}

internal data class ChatUsageLabels(val tokens: String?, val cost: String?)

@Composable
internal fun chatUsageLabels(entry: SessionDirectoryEntry?, usage: UsageState?): ChatUsageLabels {
    val session = usage?.sessions?.firstOrNull { it.id == entry?.id }
    val counts = session?.counts ?: entry?.usageSnapshot?.counts ?: return ChatUsageLabels(null, null)
    val prices = usage?.prices
    var rates by remember(prices) { mutableStateOf<Map<UsageModel, UsageRate>>(emptyMap()) }
    LaunchedEffect(prices) {
        if (prices != null) rates = try { prices.load() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { emptyMap() }
    }
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.getDefault()
    val number = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val currency = remember(locale) { NumberFormat.getCurrencyInstance(locale).apply {
        this.currency = Currency.getInstance("USD")
        maximumFractionDigits = 6
    } }
    val totals = remember(session, rates) {
        var cost = BigDecimal.ZERO
        var rated = 0L
        session?.buckets?.forEach { bucket ->
            bucket.model?.let(rates::get)?.let { rate ->
                cost += rate.cost(bucket.counts)
                rated += bucket.counts.total.coerceAtMost(Long.MAX_VALUE - rated)
            }
        }
        cost to rated
    }
    return ChatUsageLabels(stringResource(R.string.chat_audit_usage_total, number.format(counts.total)),
        when {
            totals.second <= 0 -> stringResource(R.string.chat_audit_cost_unavailable)
            totals.second < counts.total -> stringResource(R.string.chat_audit_partial_cost, currency.format(totals.first))
            else -> stringResource(R.string.chat_audit_estimated_cost, currency.format(totals.first))
        })
}
