package cn.edu.ustc.tokenworks

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale
import kotlinx.coroutines.CancellationException

private enum class UsageGrouping(@param:StringRes val label: Int) {
    SESSION(R.string.usage_by_session), MODEL(R.string.usage_by_model), DAY(R.string.usage_by_day),
}

private class UsageSummary {
    var counts = UsageCounts()
        private set
    var cost: BigDecimal? = null
        private set
    var unpriced = false
        private set

    fun add(amount: UsageCounts, estimate: BigDecimal?) {
        counts += amount
        if (estimate == null) unpriced = true
        else cost = (cost ?: BigDecimal.ZERO) + estimate
    }
}

private class UsageDashboard(sessions: List<UsageSession>, rates: Map<UsageModel, UsageRate>) {
    val total = UsageSummary()
    val bySession = ArrayList<Pair<UsageSession, UsageSummary>>(sessions.size)
    val byModel = LinkedHashMap<UsageModel?, UsageSummary>()
    val byDay = LinkedHashMap<LocalDate?, UsageSummary>()

    init {
        for (session in sessions.sortedByDescending { it.counts.total }) {
            val summary = UsageSummary()
            bySession += session to summary
            for (bucket in session.buckets) {
                val amount = bucket.counts
                if (amount.total == 0L) continue
                val estimate = bucket.model?.let { rates[it]?.cost(amount) }
                total.add(amount, estimate)
                summary.add(amount, estimate)
                byModel.getOrPut(bucket.model, ::UsageSummary).add(amount, estimate)
                byDay.getOrPut(bucket.day, ::UsageSummary).add(amount, estimate)
            }
        }
    }
}

@Composable
internal fun UsageScreen(state: UsageState, onBack: () -> Unit) {
    val store = state.prices
    var pricesOpen by rememberSaveable(store) { mutableStateOf(false) }
    var rates by remember(store) { mutableStateOf<Map<UsageModel, UsageRate>?>(null) }
    var readFailed by remember(store) { mutableStateOf(false) }
    var editOpen by rememberSaveable(store) { mutableStateOf(false) }
    var editProvider by rememberSaveable(store) { mutableStateOf<String?>(null) }
    var editModel by rememberSaveable(store) { mutableStateOf<String?>(null) }
    val back: () -> Unit = {
        when {
            editOpen -> editOpen = false
            pricesOpen -> pricesOpen = false
            else -> onBack()
        }
    }
    val locale = LocalConfiguration.current.locales[0]
    LaunchedEffect(store) {
        if (store != null) {
            try { rates = store.load() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { readFailed = true }
        }
    }
    BackHandler(pricesOpen, onBack = back)
    Scaffold(topBar = {
        TokenWorksTopBar(
            title = stringResource(if (pricesOpen) R.string.usage_prices else R.string.usage_title),
            contentMaxWidth = TokenWorksSpacing.readingWidth,
            navigationIcon = {
                IconButton(back, Modifier.sizeIn(minWidth = TokenWorksSpacing.minimumTouchTarget,
                    minHeight = TokenWorksSpacing.minimumTouchTarget)) {
                    Icon(painterResource(R.drawable.ic_back), stringResource(R.string.usage_back))
                }
            },
        )
    }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            if (pricesOpen && store != null) {
                val editing = if (editProvider != null && editModel != null) UsageModel(editProvider!!, editModel!!) else null
                UsagePricesPage(store, rates, readFailed, editOpen, editing, locale,
                    onEdit = {
                        editProvider = it?.provider
                        editModel = it?.model
                        editOpen = true
                    },
                    onSaved = { rates = it; editOpen = false },
                    onCancel = { editOpen = false },
                    modelExample = state.sessions.firstNotNullOfOrNull { session -> session.buckets.firstNotNullOfOrNull { it.model } },
                )
            } else {
                UsageDashboardPage(state, rates.orEmpty(), locale) { pricesOpen = true }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UsageDashboardPage(state: UsageState, rates: Map<UsageModel, UsageRate>, locale: Locale, openPrices: () -> Unit) {
    var group by rememberSaveable { mutableStateOf(UsageGrouping.SESSION) }
    val dashboard = remember(state.sessions, rates) { UsageDashboard(state.sessions, rates) }
    val numbers = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val currency = remember(locale) { NumberFormat.getCurrencyInstance(locale).apply {
        this.currency = Currency.getInstance("USD")
        maximumFractionDigits = 6
    } }
    val dates = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    LazyColumn(
        Modifier.widthIn(max = TokenWorksSpacing.readingWidth).fillMaxWidth(),
        contentPadding = PaddingValues(TokenWorksSpacing.lg), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.md),
    ) {
        item("scope") { Text(stringResource(R.string.usage_scope), style = MaterialTheme.typography.bodyMedium) }
        if (state.prices != null) item("prices") {
            OutlinedButton(openPrices, Modifier.fillMaxWidth().heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                Text(stringResource(R.string.usage_prices))
            }
        }
        if (state.liveUnavailable) item("unavailable") {
            TokenWorksStatusNotice(stringResource(R.string.usage_feed_unavailable), tone = TokenWorksNoticeTone.WARNING)
        }
        if (state.sessions.isEmpty()) {
            item("empty") { Text(stringResource(if (!state.runtimeSelected) R.string.usage_no_runtime else R.string.usage_empty)) }
        } else {
            item("total") { UsageCard(stringResource(R.string.usage_total), dashboard.total, numbers, currency) }
            item("grouping") {
                Column {
                    Text(stringResource(R.string.usage_grouping), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                    FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                        for (choice in UsageGrouping.entries) FilterChip(
                            selected = group == choice, onClick = { group = choice },
                            label = { Text(stringResource(choice.label)) },
                            modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget),
                        )
                    }
                }
            }
            if (group == UsageGrouping.DAY) {
                val days = dashboard.byDay.mapNotNull { (day, summary) -> day?.let { it to summary.counts.total } }
                    .sortedBy { it.first }.takeLast(30)
                if (days.isNotEmpty()) item("daily-chart") { DailyUsageChart(days, numbers, dates) }
            }
            val modelMaximum = dashboard.byModel.values.maxOfOrNull { it.counts.total } ?: 0L
            when (group) {
                UsageGrouping.SESSION -> items(dashboard.bySession, key = { "session:${it.first.id}" }) { (session, summary) ->
                    UsageCard(session.title ?: session.id, summary, numbers, currency)
                }
                UsageGrouping.MODEL -> items(dashboard.byModel.entries.sortedByDescending { it.value.counts.total },
                    key = { (model, _) -> if (model == null) "model:unknown" else "model:${model.provider.length}:${model.provider}${model.model}" }) { (model, summary) ->
                    UsageCard(model?.let { usageModelName(it) } ?: stringResource(R.string.usage_unknown_model), summary, numbers, currency,
                        fraction = summary.counts.total.toDouble().div(modelMaximum.coerceAtLeast(1L)).toFloat())
                }
                UsageGrouping.DAY -> items(dashboard.byDay.entries.sortedWith(compareByDescending { it.key }),
                    key = { "day:${it.key}" }) { (day, summary) ->
                    UsageCard(day?.format(dates) ?: stringResource(R.string.usage_unknown_day), summary, numbers, currency)
                }
            }
        }
    }
}

@Composable
private fun UsageCard(label: String, summary: UsageSummary, numbers: NumberFormat, currency: NumberFormat, fraction: Float? = null) {
    val counts = summary.counts
    Surface(Modifier.fillMaxWidth(), shape = tokenWorksSquircle(TokenWorksRadius.lg), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(TokenWorksSpacing.md), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.xs)) {
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.usage_token_total, numbers.format(counts.total)), style = MaterialTheme.typography.titleLarge)
            if (fraction != null && fraction > 0f) {
                val shape = tokenWorksSquircle(TokenWorksRadius.sm)
                Box(Modifier.fillMaxWidth().height(8.dp).background(MaterialTheme.colorScheme.surfaceVariant, shape)) {
                    Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(8.dp).background(MaterialTheme.colorScheme.primary, shape))
                }
            }
            Text(stringResource(R.string.usage_input_output, numbers.format(counts.input), numbers.format(counts.output)),
                style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.usage_cache, numbers.format(counts.cacheRead), numbers.format(counts.cacheWrite)),
                style = MaterialTheme.typography.bodyMedium)
            val amount = summary.cost
            Text(if (amount == null) stringResource(R.string.usage_no_cost) else stringResource(
                if (summary.unpriced) R.string.usage_partial_cost else R.string.usage_cost, currency.format(amount)),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DailyUsageChart(days: List<Pair<LocalDate, Long>>, numbers: NumberFormat, dates: DateTimeFormatter) {
    val peak = days.maxOf { it.second }.coerceAtLeast(1L)
    val first = days.first().first
    val last = days.last().first
    val daysWide = (last.toEpochDay() - first.toEpochDay()).coerceAtLeast(1L).toDouble()
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val title = stringResource(R.string.usage_daily_chart_title)
    val description = stringResource(R.string.usage_daily_chart_summary, first.format(dates), last.format(dates), numbers.format(peak))
    Surface(Modifier.fillMaxWidth(), shape = tokenWorksSquircle(TokenWorksRadius.lg), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(TokenWorksSpacing.md), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.usage_token_total, numbers.format(peak)), style = MaterialTheme.typography.labelMedium)
            Canvas(Modifier.fillMaxWidth().height(112.dp).semantics { contentDescription = description }) {
                for (tick in 0..2) {
                    val y = 3.dp.toPx() + (size.height - 6.dp.toPx()) * tick / 2f
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }
                var previous: Offset? = null
                for ((day, tokens) in days) {
                    val x = if (days.size == 1) size.width / 2 else
                        ((day.toEpochDay() - first.toEpochDay()) / daysWide * (size.width - 8.dp.toPx())).toFloat() + 4.dp.toPx()
                    val y = size.height - 3.dp.toPx() - (tokens.toDouble() / peak * (size.height - 6.dp.toPx())).toFloat()
                    val point = Offset(x, y)
                    previous?.let { drawLine(primary, it, point, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round) }
                    drawCircle(primary, radius = 3.dp.toPx(), center = point)
                    previous = point
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.md)) {
                Text(first.format(dates), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                if (first != last) Text(last.format(dates), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
internal fun usageModelName(model: UsageModel) = stringResource(R.string.usage_model_name, model.provider, model.model)
