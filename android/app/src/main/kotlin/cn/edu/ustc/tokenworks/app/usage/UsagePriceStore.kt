package cn.edu.ustc.tokenworks

import android.content.Context
import java.io.IOException
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** USD per million tokens, with a separate price for each disjoint billing bucket. */
internal data class UsageRate(val input: BigDecimal, val output: BigDecimal, val cacheRead: BigDecimal, val cacheWrite: BigDecimal) {
    fun cost(counts: UsageCounts): BigDecimal = (
        input * counts.input.toBigDecimal() + output * counts.output.toBigDecimal() +
            cacheRead * counts.cacheRead.toBigDecimal() + cacheWrite * counts.cacheWrite.toBigDecimal()
        ).movePointLeft(6)

    companion object {
        private val decimal = Regex("(?:[0-9]{1,8}(?:\\.[0-9]{0,6})?|\\.[0-9]{1,6})")
        fun parseAmount(value: String): BigDecimal? = decimal.matchEntire(value)?.value?.toBigDecimal()
    }
}

/** Preferences contain only user-entered prices, not per-session usage or messages. */
internal class UsagePriceStore(context: Context, private val ownerKey: String) {
    private val application = context.applicationContext
    private fun preferences() = application.getSharedPreferences("usage-prices-v1-$ownerKey", Context.MODE_PRIVATE)

    suspend fun load(): Map<UsageModel, UsageRate> = withContext(Dispatchers.IO) {
        val raw = preferences().getString("rates", null) ?: return@withContext emptyMap()
        val table = SessionJson.parse(raw.toByteArray()).array("rates")
        buildMap {
            for (value in table) {
                val row = value as? JsonObject ?: throw IOException("invalid price row")
                fun amount(key: String) = UsageRate.parseAmount(row.string(key)) ?: throw IOException("invalid price")
                put(UsageModel(row.string("provider"), row.string("model")), UsageRate(
                    amount("input"), amount("output"), amount("cacheRead"), amount("cacheWrite"),
                ))
            }
        }
    }

    suspend fun save(rates: Map<UsageModel, UsageRate>): Unit = withContext(Dispatchers.IO) {
        val rows = rates.map { (model, rate) ->
            jsonObject("provider" to model.provider.json(), "model" to model.model.json(), "input" to rate.input.toPlainString().json(),
                "output" to rate.output.toPlainString().json(), "cacheRead" to rate.cacheRead.toPlainString().json(),
                "cacheWrite" to rate.cacheWrite.toPlainString().json())
        }
        val encoded = SessionJson.encode(jsonObject("rates" to JsonArray(rows))).toString(Charsets.UTF_8)
        if (!preferences().edit().putString("rates", encoded).commit()) throw IOException("price table write failed")
    }
}
