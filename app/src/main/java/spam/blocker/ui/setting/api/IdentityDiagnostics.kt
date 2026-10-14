package spam.blocker.ui.setting.api

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import spam.blocker.G
import spam.blocker.R
import spam.blocker.db.NumberIdentityCache
import spam.blocker.ui.setting.*
import spam.blocker.ui.widgets.GreyText
import spam.blocker.ui.widgets.Str
import spam.blocker.ui.widgets.SwitchBox
import spam.blocker.util.*

@Composable
fun IdentityDiagnostics(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val resources = LocalResources.current
    val prefs = remember { spf.ApiQueryOptions(ctx) }
    val scope = rememberCoroutineScope()
    var number by remember { mutableStateOf("") }
    var timeout by remember { mutableIntStateOf(prefs.identityTestTimeoutMs.coerceIn(1000, 30_000)) }
    var retryFailures by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var result by remember { mutableStateOf<NumberIdentity?>(null) }
    var elapsed by remember { mutableLongStateOf(0) }
    var message by remember { mutableStateOf("") }
    fun query() {
        val normalized = CnNumberLookup.normalizeNumber(ctx, number)?.e164
        if (normalized == null) { message = resources.getString(R.string.cn_invalid_number); return }
        val budget = timeout
        val retry = retryFailures
        busy = true; message = ""; result = null
        job = scope.launch {
            val start = SystemClock.elapsedRealtime()
            try {
                val (_, local) = withContext(Dispatchers.IO) { IdentityLookup.offline(ctx, normalized) }
                result = NumberIdentity.merge(normalized, local, emptyList())
                result = withContext(Dispatchers.IO) {
                    IdentityLookup.online(ctx, normalized, local, timeoutMs = budget, retryFailures = retry)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = resources.getString(R.string.cn_query_failed) }
            finally { elapsed = SystemClock.elapsedRealtime() - start; busy = false }
        }
    }
    SettingsPage(Str(R.string.ui_identity_test), onDismiss) {
        Text(Str(R.string.ui_test_saved_hint), modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            fontSize = 13.sp, color = G.palette.textGrey)
        SettingsGroup(Str(R.string.ui_query_options)) {
            OutlinedTextField(number, { number = it; message = "" }, modifier = Modifier.fillMaxWidth(),
                label = { Text(Str(R.string.cn_test_number)) }, singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
            SettingsSubheading(Str(R.string.ui_test_timeout))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5000, 15000, 30000).forEach { value ->
                    FilterChip(selected = timeout == value, enabled = !busy, onClick = {
                        timeout = value; prefs.identityTestTimeoutMs = value
                    }, label = { Text(resources.getString(R.string.ui_seconds, value / 1000)) })
                }
            }
            LabeledRow(R.string.ui_retry_failures) { SwitchBox(retryFailures) { if (!busy) retryFailures = it } }
            GreyText(Str(R.string.ui_retry_hint))
            Spacer(Modifier.height(12.dp))
            Button(onClick = { if (busy) job?.cancel() else query() }, modifier = Modifier.fillMaxWidth()) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                }
                Text(Str(if (busy) R.string.ui_cancel_query else R.string.cn_query_test))
            }
        }
        if (message.isNotBlank()) Text(message, color = G.palette.error, modifier = Modifier.padding(horizontal = 24.dp))
        result?.let { identity ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(Str(R.string.ui_query_results))
                Text(if (busy) Str(R.string.cn_lookup_loading) else resources.getString(R.string.ui_elapsed, elapsed),
                    color = G.palette.textGrey, fontSize = 12.sp)
            }
            identity.evidence.forEach { IdentityResultCard(it) }
            if (!busy && identity.evidence.size == 1) Text(Str(R.string.ui_online_skipped),
                fontSize = 12.sp, color = G.palette.textGrey, modifier = Modifier.padding(horizontal = 24.dp))
            if (identity.conflict) Text(Str(R.string.cn_risk_conflict), color = G.palette.warning,
                modifier = Modifier.padding(horizontal = 24.dp))
            Text(Str(R.string.ui_mark_not_safety), color = G.palette.textGrey, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        }
    }
}

@Composable
fun IdentityResultCard(evidence: IdentityEvidence) {
    val ctx = LocalContext.current
    val resources = LocalResources.current
    var details by remember(evidence) { mutableStateOf(false) }
    val local = evidence.sourceId == "offline"
    val failed = evidence.status in listOf("error", "timeout")
    val status = when {
        evidence.status == "timeout" -> R.string.ui_timed_out
        failed -> R.string.ui_failed
        evidence.status == "empty" || !evidence.hasData() -> R.string.ui_no_result
        else -> R.string.ui_has_result
    }
    SettingsGroup(if (local) Str(R.string.ui_local_database) else evidence.source) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatusPill(Str(status), if (failed) G.palette.error else G.palette.infoBlue)
            Text(Str(if (local) R.string.ui_origin_local else if (evidence.fromCache) R.string.ui_origin_cache else R.string.ui_origin_online),
                color = G.palette.textGrey, fontSize = 12.sp)
        }
        val fields = listOfNotNull(evidence.name,
            listOfNotNull(evidence.province, evidence.city).distinct().joinToString(" · ").ifBlank { null },
            evidence.carrier, evidence.category, evidence.company, evidence.info).distinct()
        fields.forEach { Text(it, modifier = Modifier.padding(top = 8.dp), fontSize = 14.sp) }
        evidence.failureStage?.let { stage ->
            val label = when (stage) {
                "prepare" -> R.string.ui_stage_prepare
                "connect" -> R.string.ui_stage_connect
                "request" -> R.string.ui_stage_request
                "response" -> R.string.ui_stage_response
                "read" -> R.string.ui_stage_read
                "parse" -> R.string.ui_stage_parse
                else -> R.string.ui_stage_deadline
            }
            Text(resources.getString(R.string.ui_failure_stage, resources.getString(label)), fontSize = 12.sp,
                color = G.palette.textGrey, modifier = Modifier.padding(top = 8.dp))
        }
        TextButton(onClick = { details = !details }, contentPadding = PaddingValues(0.dp)) { Text(Str(R.string.ui_details)) }
        if (details) {
            Text(evidence.source, fontSize = 12.sp, color = G.palette.textGrey)
            if (evidence.queriedAt > 0) Text(android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss", evidence.queriedAt).toString(),
                fontSize = 12.sp, color = G.palette.textGrey)
        }
    }
}

@Composable
fun IdentityDataPage(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var counts by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var paid by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    LaunchedEffect(revision) {
        withContext(Dispatchers.IO) {
            runCatching { NumberIdentityCache(ctx).stats() to PhoneNumberMarkedDatabase.savedResultCount(ctx) }
        }.onSuccess { counts = it.first; paid = it.second }
    }
    fun clear(kind: String) {
        confirm = null; busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val cache = NumberIdentityCache(ctx)
                    when (kind) {
                        "failures" -> cache.clearFailures()
                        "general" -> cache.clear()
                        "paid" -> {
                            check(PhoneNumberMarkedDatabase.clearApiCache(ctx))
                            cache.clearSource("tmini-checkmark")
                        }
                    }
                }
                message = resources.getString(R.string.cn_cache_cleared); revision++
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = resources.getString(R.string.cn_query_failed) }
            finally { busy = false }
        }
    }
    confirm?.let { kind ->
        AlertDialog(onDismissRequest = { confirm = null },
            title = { Text(Str(if (kind == "paid") R.string.ui_delete_paid else R.string.ui_clear_general)) },
            text = { Text(Str(if (kind == "paid") R.string.ui_delete_paid_confirm else R.string.ui_clear_general_confirm)) },
            confirmButton = { TextButton(onClick = { clear(kind) }) { Text(Str(R.string.ui_confirm_delete), color = G.palette.error) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(Str(R.string.ui_cancel)) } })
    }
    SettingsPage(Str(R.string.ui_identity_data), { if (!busy) onDismiss() }) {
        Text(Str(R.string.ui_data_intro), color = G.palette.textGrey, fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        if (message.isNotBlank()) Text(message, modifier = Modifier.padding(horizontal = 24.dp))
        SettingsGroup(Str(R.string.ui_failure_cache)) {
            Text(resources.getString(R.string.ui_records, counts?.second?.toString() ?: "—"))
            GreyText(Str(R.string.ui_failure_cache_hint))
            OutlinedButton(onClick = { clear("failures") }, enabled = !busy) { Text(Str(R.string.ui_clear_failures)) }
        }
        SettingsGroup(Str(R.string.ui_general_cache)) {
            Text(resources.getString(R.string.ui_records, counts?.first?.toString() ?: "—"))
            GreyText(Str(R.string.ui_general_cache_hint))
            OutlinedButton(onClick = { confirm = "general" }, enabled = !busy) { Text(Str(R.string.ui_clear_general)) }
        }
        SettingsGroup(Str(R.string.ui_paid_data)) {
            Text(resources.getString(R.string.ui_records, paid?.toString() ?: "—"))
            GreyText(Str(R.string.ui_paid_data_hint))
            TextButton(onClick = { confirm = "paid" }, enabled = !busy) { Text(Str(R.string.ui_delete_paid), color = G.palette.error) }
        }
        Text(Str(R.string.ui_backup_limit), color = G.palette.textGrey, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    }
}
