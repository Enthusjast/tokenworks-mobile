package cn.edu.ustc.tokenworks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import java.math.BigDecimal
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun UsagePricesPage(
    store: UsagePriceStore, rates: Map<UsageModel, UsageRate>?, readFailed: Boolean, editOpen: Boolean,
    editing: UsageModel?, locale: Locale, onEdit: (UsageModel?) -> Unit, onSaved: (Map<UsageModel, UsageRate>) -> Unit,
    onCancel: () -> Unit, modelExample: UsageModel?,
) {
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun save(next: Map<UsageModel, UsageRate>) {
        if (saving) return
        saving = true
        saveFailed = false
        scope.launch {
            try { store.save(next); onSaved(next) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { saveFailed = true }
            finally { saving = false }
        }
    }
    val buttonSize = Modifier.fillMaxWidth().heightIn(min = TokenWorksSpacing.minimumTouchTarget)
    LazyColumn(
        Modifier.widthIn(max = TokenWorksSpacing.readingWidth).fillMaxWidth(), contentPadding = PaddingValues(TokenWorksSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.md),
    ) {
        item("help") { Text(stringResource(R.string.usage_prices_help), style = MaterialTheme.typography.bodyMedium) }
        when {
            readFailed -> item("load-error") {
                TokenWorksStatusNotice(stringResource(R.string.usage_prices_load_failed), tone = TokenWorksNoticeTone.ERROR)
            }
            rates == null -> item("loading") {
                Column(verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                    Text(stringResource(R.string.usage_prices_loading))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            editOpen -> item("editor") {
                UsagePriceForm(editing, rates, locale, modelExample, saving,
                    onSave = { model, price -> save(rates + (model to price)) },
                    onRemove = { model -> save(rates - model) }, onCancel = { saveFailed = false; onCancel() },
                    saveFailed = saveFailed,
                )
            }
            else -> {
                item("add") {
                    Button(onClick = { saveFailed = false; onEdit(null) }, modifier = buttonSize) {
                        Text(stringResource(R.string.usage_price_add))
                    }
                }
                if (rates.isEmpty()) item("empty") { Text(stringResource(R.string.usage_prices_empty)) }
                items(rates.entries.sortedWith(compareBy({ it.key.provider }, { it.key.model })),
                    key = { (key, _) -> "price:${key.provider.length}:${key.provider}${key.model}" }) { (model, rate) ->
                    Surface(Modifier.fillMaxWidth(), shape = tokenWorksSquircle(TokenWorksRadius.lg), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(TokenWorksSpacing.md), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                            val name = usageModelName(model)
                            Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                            Text(stringResource(R.string.usage_price_per_million, rate.input.toPlainString(), rate.output.toPlainString(),
                                rate.cacheRead.toPlainString(), rate.cacheWrite.toPlainString()), style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(onClick = { saveFailed = false; onEdit(model) }, modifier = buttonSize) {
                                Text(stringResource(R.string.usage_price_edit_model, name))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsagePriceForm(
    editing: UsageModel?, rates: Map<UsageModel, UsageRate>, locale: Locale, modelExample: UsageModel?, saving: Boolean,
    onSave: (UsageModel, UsageRate) -> Unit, onRemove: (UsageModel) -> Unit, onCancel: () -> Unit, saveFailed: Boolean,
) {
    val existing = editing?.let { rates[it] }
    var provider by rememberSaveable(editing) { mutableStateOf(editing?.provider.orEmpty()) }
    var model by rememberSaveable(editing) { mutableStateOf(editing?.model.orEmpty()) }
    var input by rememberSaveable(editing) { mutableStateOf(existing?.input?.toPlainString().orEmpty()) }
    var output by rememberSaveable(editing) { mutableStateOf(existing?.output?.toPlainString().orEmpty()) }
    var cacheRead by rememberSaveable(editing) { mutableStateOf(existing?.cacheRead?.toPlainString().orEmpty()) }
    var cacheWrite by rememberSaveable(editing) { mutableStateOf(existing?.cacheWrite?.toPlainString().orEmpty()) }
    var submitted by rememberSaveable(editing) { mutableStateOf(false) }
    val separator = remember(locale) { DecimalFormatSymbols.getInstance(locale).decimalSeparator }
    fun parse(raw: String): BigDecimal? = UsageRate.parseAmount(raw.trim().replace(separator, '.'))
    fun validId(text: String, max: Int): Boolean {
        val trimmed = text.trim()
        return trimmed.isNotEmpty() && trimmed.length <= max && DirectoryDisplayMetadata.native(trimmed, null, null, null, false).title != null
    }
    val providerValid = validId(provider, 128)
    val modelValid = validId(model, 256)
    val key = if (providerValid && modelValid) UsageModel(provider.trim(), model.trim()) else null
    val duplicate = key != null && editing == null && key in rates
    val buttonSize = Modifier.fillMaxWidth().heightIn(min = TokenWorksSpacing.minimumTouchTarget)
    Column(verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
        Text(stringResource(if (editing == null) R.string.usage_price_add else R.string.usage_price_edit),
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        if (editing == null) {
            OutlinedTextField(provider, { provider = it }, Modifier.fillMaxWidth(), enabled = !saving, singleLine = true,
                label = { Text(stringResource(R.string.usage_price_provider)) },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), isError = submitted && !providerValid,
                supportingText = if (submitted && !providerValid) ({ Text(stringResource(R.string.usage_price_required_id)) }) else null)
            OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), enabled = !saving, singleLine = true,
                label = { Text(stringResource(R.string.usage_price_model)) },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), isError = submitted && !modelValid,
                supportingText = if (submitted && !modelValid) ({ Text(stringResource(R.string.usage_price_required_id)) }) else null)
            if (modelExample != null) Text(stringResource(R.string.usage_price_model_example, usageModelName(modelExample)),
                style = MaterialTheme.typography.bodySmall)
        } else Text(usageModelName(editing), style = MaterialTheme.typography.bodyMedium)
        val theInput = parse(input)
        val theOutput = parse(output)
        val theCacheRead = parse(cacheRead)
        val theCacheWrite = parse(cacheWrite)
        UsagePriceField(input, { input = it }, R.string.usage_price_input, !saving, submitted && theInput == null)
        UsagePriceField(output, { output = it }, R.string.usage_price_output, !saving, submitted && theOutput == null)
        UsagePriceField(cacheRead, { cacheRead = it }, R.string.usage_price_cache_read, !saving, submitted && theCacheRead == null)
        UsagePriceField(cacheWrite, { cacheWrite = it }, R.string.usage_price_cache_write, !saving, submitted && theCacheWrite == null)
        if ((submitted && duplicate) || saveFailed) TokenWorksStatusNotice(
            stringResource(if (duplicate) R.string.usage_price_duplicate else R.string.usage_prices_save_failed),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, tone = TokenWorksNoticeTone.ERROR,
        )
        if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(onClick = {
            submitted = true
            if (key != null && !duplicate && theInput != null && theOutput != null && theCacheRead != null && theCacheWrite != null) {
                onSave(key, UsageRate(theInput, theOutput, theCacheRead, theCacheWrite))
            }
        }, modifier = buttonSize, enabled = !saving) { Text(stringResource(R.string.usage_price_save)) }
        if (editing != null) OutlinedButton(onClick = { onRemove(editing) }, modifier = buttonSize, enabled = !saving) {
            Text(stringResource(R.string.usage_price_remove))
        }
        TextButton(onCancel, buttonSize, enabled = !saving) { Text(stringResource(R.string.usage_price_cancel)) }
    }
}

@Composable
private fun UsagePriceField(value: String, onChange: (String) -> Unit, label: Int, enabled: Boolean, invalid: Boolean) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), enabled = enabled, singleLine = true,
        label = { Text(stringResource(label)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = invalid,
        supportingText = if (invalid) ({ Text(stringResource(R.string.usage_price_improper_amount)) }) else null)
}
