package spam.blocker.ui.setting.api

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import spam.blocker.G
import spam.blocker.R
import spam.blocker.db.NumberIdentityCache
import spam.blocker.ui.setting.*
import spam.blocker.ui.widgets.GreyText
import spam.blocker.ui.widgets.NumberInputBox
import spam.blocker.ui.widgets.Str
import spam.blocker.ui.widgets.StrInputBox
import spam.blocker.ui.widgets.SwitchBox
import spam.blocker.util.*

@Composable
fun IdentitySettings() {
    val ctx = LocalContext.current
    val resources = LocalResources.current
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val prefs = spf.ApiQueryOptions(ctx)
    val count = remember(revision) { runCatching {
        IdentityJson.sources(prefs.identitySources).count { it.enabled }
    }.getOrDefault(0) }
    SettingsNavigationRow(Str(R.string.ui_identity_sources),
        if (prefs.onlyOffline || count == 0) Str(R.string.ui_local_only_status)
        else resources.getString(R.string.ui_sources_enabled, count), R.drawable.ic_settings) { page = "sources" }
    SettingsNavigationRow(Str(R.string.ui_identity_test), Str(R.string.ui_test_entry_summary), R.drawable.ic_tube) { page = "test" }
    when (page) {
        "sources" -> IdentityConfiguration(onDismiss = { page = null }, onSaved = { revision++ })
        "test" -> IdentityDiagnostics(onDismiss = { page = null })
    }
}

@Composable
fun IdentityDataSettings() {
    var opened by rememberSaveable { mutableStateOf(false) }
    SettingsNavigationRow(Str(R.string.ui_identity_data), Str(R.string.ui_data_entry_summary), R.drawable.ic_history_cleanup) { opened = true }
    if (opened) IdentityDataPage { opened = false }
}

@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, label: String) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { TextButton(onClick = { visible = !visible }) {
            Text(Str(if (visible) R.string.ui_hide_key else R.string.ui_show_key))
        } })
}

@Composable
private fun IdentityConfiguration(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val resources = LocalResources.current
    val prefs = remember { spf.ApiQueryOptions(ctx) }
    val scope = rememberCoroutineScope()
    var sources by remember { mutableStateOf(prefs.identitySources) }
    var policy by remember { mutableStateOf(prefs.identityCachePolicy) }
    var timeout by remember { mutableIntStateOf(prefs.identityTimeoutMs) }
    var onlyOffline by remember { mutableStateOf(prefs.onlyOffline) }
    var ignored by remember { mutableStateOf(prefs.ignoredNumberPrefixes) }
    var apiType by remember { mutableIntStateOf(prefs.apiType) }
    var baiduKey by remember { mutableStateOf(prefs.baiduApiKey) }
    var juheKey by remember { mutableStateOf(prefs.juheApiKey) }
    var tminiKey by remember { mutableStateOf(prefs.tminiApiKey) }
    var advanced by remember { mutableStateOf(runCatching { IdentityJson.sources(sources) }.isFailure) }
    var addMenu by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    val parsed = runCatching { IdentityJson.sources(sources) }.getOrNull()
    val dirty = sources != prefs.identitySources || policy != prefs.identityCachePolicy || timeout != prefs.identityTimeoutMs ||
        onlyOffline != prefs.onlyOffline || ignored != prefs.ignoredNumberPrefixes || apiType != prefs.apiType ||
        baiduKey != prefs.baiduApiKey || juheKey != prefs.juheApiKey || tminiKey != prefs.tminiApiKey
    fun dismiss() { if (!busy) { if (dirty) discard = true else onDismiss() } }
    fun update(list: List<IdentitySource>) { sources = IdentityJson.json.encodeToString(list); message = "" }
    fun add(source: IdentitySource) {
        addMenu = false
        val list = parsed ?: run { advanced = true; failed = true; message = resources.getString(R.string.cn_invalid_config); return }
        if (list.any { it.id == source.id }) return
        if (list.size >= 6) { failed = true; message = resources.getString(R.string.ui_max_sources); return }
        update(list + source)
        if (source.id.startsWith("custom")) advanced = true
    }
    fun save() {
        val validated = runCatching {
            val list = IdentityJson.sources(sources)
            IdentityJson.json.decodeFromString<IdentityCachePolicy>(policy)
            require(timeout in 250..5000)
            require(list.none { it.enabled &&
                ((it.parameters.values.any { v -> v.contains("shared_pref(tmini_api_key)") } && tminiKey.isBlank()) ||
                (it.parameters.values.any { v -> v.contains("shared_pref(juhe_api_key)") } && juheKey.isBlank())) })
        }
        if (validated.isFailure) { failed = true; message = resources.getString(R.string.ui_config_invalid); return }
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { NumberIdentityCache(ctx).clearFailures() }
                prefs.identitySources = sources; prefs.identityCachePolicy = policy; prefs.identityTimeoutMs = timeout
                prefs.onlyOffline = onlyOffline; prefs.ignoredNumberPrefixes = ignored; prefs.apiType = apiType
                prefs.baiduApiKey = baiduKey.trim(); prefs.juheApiKey = juheKey.trim(); prefs.tminiApiKey = tminiKey.trim()
                baiduKey = prefs.baiduApiKey; juheKey = prefs.juheApiKey; tminiKey = prefs.tminiApiKey
                failed = false; message = resources.getString(R.string.cn_saved); onSaved()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { failed = true; message = resources.getString(R.string.cn_query_failed) }
            finally { busy = false }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(Str(R.string.ui_unsaved_title)) },
        text = { Text(Str(R.string.ui_unsaved_body)) },
        confirmButton = { TextButton(onClick = { discard = false; onDismiss() }) { Text(Str(R.string.ui_discard)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(Str(R.string.ui_keep_editing)) } })

    SettingsPage(Str(R.string.ui_identity_sources), ::dismiss,
        actions = { TextButton(onClick = ::save, enabled = !busy && dirty) { Text(Str(R.string.cn_save)) } }) {
        Text(Str(R.string.ui_identity_intro), color = G.palette.textGrey, fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        if (message.isNotBlank()) Text(message, color = if (failed) G.palette.error else G.palette.success,
            modifier = Modifier.padding(horizontal = 24.dp))
        SettingsGroup(Str(R.string.ui_lookup_strategy)) {
            LabeledRow(R.string.cn_only_offline) { SwitchBox(onlyOffline) { onlyOffline = it } }
            GreyText(Str(R.string.ui_paid_local_first))
        }
        parsed?.forEach { source ->
            SettingsGroup(when (source.id) {
                "tmini-checkmark" -> Str(R.string.ui_tmini_service)
                "callerinfo-juhe" -> Str(R.string.ui_juhe_service)
                else -> source.name
            }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatusPill(Str(if (source.enabled) R.string.ui_enabled else R.string.ui_disabled),
                        if (source.enabled) G.palette.infoBlue else G.palette.textGrey)
                    SwitchBox(source.enabled) { value -> update(parsed.map { if (it.id == source.id) it.copy(enabled = value) else it }) }
                }
                Spacer(Modifier.height(8.dp))
                when (source.id) {
                    "tmini-checkmark" -> SecretField(tminiKey, { tminiKey = it }, Str(R.string.cn_tmini_key))
                    "callerinfo-juhe" -> SecretField(juheKey, { juheKey = it }, Str(R.string.cn_juhe_key))
                    else -> GreyText(Str(R.string.ui_custom_edit_hint))
                }
                if (source.id == "tmini-checkmark") Text(Str(R.string.ui_tmini_paid_hint), fontSize = 12.sp,
                    color = G.palette.textGrey, modifier = Modifier.padding(top = 8.dp))
                Text(runCatching { java.net.URI(source.url).host }.getOrNull().orEmpty(), fontSize = 12.sp,
                    color = G.palette.textGrey, modifier = Modifier.padding(top = 8.dp))
            }
        }
        if (parsed?.isEmpty() == true) SettingsGroup(Str(R.string.ui_no_sources)) { GreyText(Str(R.string.ui_no_sources_hint)) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                OutlinedButton(onClick = { addMenu = true }, enabled = !busy) { Text(Str(R.string.ui_add_service)) }
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(text = { Text(Str(R.string.ui_tmini_service)) }, onClick = { add(IdentitySourcePresets.tmini(tminiKey.isNotBlank())) })
                    DropdownMenuItem(text = { Text(Str(R.string.ui_juhe_service)) }, onClick = { add(IdentitySourcePresets.juhe(juheKey.isNotBlank())) })
                    DropdownMenuItem(text = { Text(Str(R.string.ui_custom_service)) }, onClick = {
                        val id = generateSequence(1) { it + 1 }.map { "custom-$it" }.first { candidate -> parsed?.none { it.id == candidate } != false }
                        add(IdentitySourcePresets.custom(id))
                    })
                }
            }
            TextButton(onClick = { advanced = !advanced }) { Text(Str(R.string.ui_advanced)) }
        }
        if (advanced) SettingsGroup(Str(R.string.ui_advanced)) {
            GreyText(Str(R.string.ui_advanced_hint))
            StrInputBox(sources, { sources = it; message = "" }, label = { Text(Str(R.string.cn_sources_json)) }, maxLines = 8)
            StrInputBox(ignored, { ignored = it }, label = { Text(Str(R.string.cn_ignored_prefixes)) }, singleLine = true)
            NumberInputBox(intValue = timeout, labelId = R.string.ui_auto_timeout,
                onValueChange = { value, error -> if (!error && value != null) timeout = value })
            GreyText(Str(R.string.ui_auto_timeout_hint))
            StrInputBox(policy, { policy = it }, label = { Text(Str(R.string.cn_cache_policy)) }, maxLines = 4)
            NumberInputBox(intValue = apiType, labelId = R.string.cn_api_type,
                onValueChange = { value, error -> if (!error && value != null) apiType = value })
            SecretField(baiduKey, { baiduKey = it }, Str(R.string.cn_baidu_key))
            GreyText(Str(R.string.cn_legacy_credentials_help))
        }
        Spacer(Modifier.height(16.dp))
    }
}
