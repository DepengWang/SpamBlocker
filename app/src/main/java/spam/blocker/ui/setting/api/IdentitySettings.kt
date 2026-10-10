package spam.blocker.ui.setting.api

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import spam.blocker.R
import spam.blocker.G
import spam.blocker.db.NumberIdentityCache
import spam.blocker.ui.setting.LabeledRow
import spam.blocker.ui.widgets.*
import spam.blocker.util.*

@Composable
fun IdentitySettings() {
    val ctx = LocalContext.current
    val prefs = spf.ApiQueryOptions(ctx)
    val trigger = remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var sources by remember { mutableStateOf(prefs.identitySources) }
    var policy by remember { mutableStateOf(prefs.identityCachePolicy) }
    var timeout by remember { mutableIntStateOf(prefs.identityTimeoutMs) }
    var onlyOffline by remember { mutableStateOf(prefs.onlyOffline) }
    var ignoredPrefixes by remember { mutableStateOf(prefs.ignoredNumberPrefixes) }
    var apiType by remember { mutableIntStateOf(prefs.apiType) }
    var baiduKey by remember { mutableStateOf(prefs.baiduApiKey) }
    var juheKey by remember { mutableStateOf(prefs.juheApiKey) }
    var tminiKey by remember { mutableStateOf(prefs.tminiApiKey) }
    var number by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LabeledRow(R.string.cn_identity) {
        StrokeButton(Str(R.string.cn_configure), color = G.palette.textGrey) { trigger.value = true }
    }
    PopupDialog(trigger, buttons = {
        StrokeButton(Str(R.string.cn_save), color = G.palette.textGrey, enabled = !busy) {
            try {
                IdentityJson.sources(sources)
                IdentityJson.json.decodeFromString<IdentityCachePolicy>(policy)
                require(timeout in 250..5000)
                val tminiKeyChanged = prefs.tminiApiKey != tminiKey
                prefs.identitySources = sources
                prefs.identityCachePolicy = policy
                prefs.identityTimeoutMs = timeout
                prefs.onlyOffline = onlyOffline
                prefs.ignoredNumberPrefixes = ignoredPrefixes
                prefs.apiType = apiType
                prefs.baiduApiKey = baiduKey
                prefs.juheApiKey = juheKey
                prefs.tminiApiKey = tminiKey
                message = ctx.getString(R.string.cn_saved)
                if (tminiKeyChanged) {
                    busy = true
                    scope.launch {
                        try { withContext(Dispatchers.IO) { NumberIdentityCache(ctx).clear() } }
                        finally { busy = false }
                    }
                }
            } catch (_: Exception) { message = ctx.getString(R.string.cn_invalid_config) }
        }
    }) {
        GreyText(Str(R.string.cn_identity_help))
        LabeledRow(R.string.cn_only_offline) {
            SwitchBox(onlyOffline) { onlyOffline = it }
        }
        NumberInputBox(intValue = apiType, labelId = R.string.cn_api_type,
            onValueChange = { value, error -> if (!error && value != null) apiType = value })
        StrInputBox(ignoredPrefixes, { ignoredPrefixes = it },
            label = { Text(Str(R.string.cn_ignored_prefixes)) }, singleLine = true)
        StrInputBox(baiduKey, { baiduKey = it },
            label = { Text(Str(R.string.cn_baidu_key)) }, singleLine = true)
        StrInputBox(juheKey, { juheKey = it },
            label = { Text(Str(R.string.cn_juhe_key)) }, singleLine = true)
        StrInputBox(tminiKey, { tminiKey = it },
            label = { Text(Str(R.string.cn_tmini_key)) }, singleLine = true)
        GreyText(Str(R.string.cn_legacy_credentials_help))
        StrInputBox(sources, { sources = it }, label = { Text(Str(R.string.cn_sources_json)) }, maxLines = 8)
        StrokeButton(Str(R.string.cn_example), color = G.palette.textGrey, enabled = !busy && sources.trim() in listOf("", "[]")) {
            sources = """[{"id":"custom","name":"My API","enabled":false,"url":"https://example.com/lookup?number={number}","paths":{"name":"data.name","province":"data.province","city":"data.city","carrier":"data.carrier","category":"data.category","risk":"data.riskLevel"},"successPath":"code","successValue":"0"}]"""
        }
        StrokeButton(Str(R.string.cn_legacy_api_example), color = G.palette.textGrey, enabled = !busy) {
            sources = """[{"id":"callerinfo-custom","name":"CallerInfo custom API","apiId":1000,"enabled":false,"url":"https://example.com/api","parameters":{"tel":"{domestic}","key":"{shared_pref(baidu_api_key)}"},"paths":{"name":"result.name","province":"result.province","city":"result.city","carrier":"result.provider","company":"result.company","info":"result.info"},"successPath":"error_code","successValue":"0"}]"""
        }
        StrokeButton(Str(R.string.cn_juhe_default_api), color = G.palette.textGrey, enabled = !busy) {
            val enabled = juheKey.isNotBlank()
            sources = """[{"id":"callerinfo-juhe","name":"聚合数据手机号归属地","apiId":1,"enabled":$enabled,"url":"https://apis.juhe.cn/mobile/get","parameters":{"phone":"{domestic}","key":"{shared_pref(juhe_api_key)}"},"paths":{"province":"result.province","city":"result.city","carrier":"result.company"},"successPath":"error_code","successValue":"0"}]"""
            message = if (enabled) ctx.getString(R.string.cn_juhe_default_enabled)
                else ctx.getString(R.string.cn_juhe_default_needs_key)
        }
        StrokeButton(Str(R.string.cn_tmini_mark_api), color = G.palette.textGrey, enabled = !busy) {
            val enabled = tminiKey.isNotBlank()
            sources = """[{"id":"tmini-checkmark","name":"Tmini 聚合号码标记","enabled":$enabled,"url":"https://api.tmini.net/apis/checkmark","parameters":{"key":"{shared_pref(tmini_api_key)}","phone":"{domestic}"},"paths":{"province":"region","company":"certName","category":"results.*.tags.*.name"},"successPath":"code","successValue":"200"}]"""
            message = if (enabled) ctx.getString(R.string.cn_tmini_default_enabled)
                else ctx.getString(R.string.cn_tmini_default_needs_key)
        }
        StrInputBox(policy, { policy = it }, label = { Text(Str(R.string.cn_cache_policy)) },
            helpTooltip = Str(R.string.cn_cache_policy_help), maxLines = 4)
        NumberInputBox(intValue = timeout, labelId = R.string.cn_query_timeout,
            onValueChange = { value, error -> if (!error && value != null) timeout = value })
        GreyText(Str(R.string.cn_test_help))
        StrInputBox(number, { number = it }, label = { Text(Str(R.string.cn_test_number)) }, singleLine = true)
        RowVCenterSpaced(4) {
            StrokeButton(Str(R.string.cn_query_test), color = G.palette.textGrey, enabled = !busy) {
                val normalized = CnNumberLookup.normalizeNumber(number)?.e164
                if (normalized == null) { message = ctx.getString(R.string.cn_invalid_number) }
                else {
                    busy = true
                    scope.launch {
                        try {
                            val output = withContext(Dispatchers.IO) {
                                val (_, offline) = IdentityLookup.offline(ctx, number)
                                val result = IdentityLookup.online(ctx, normalized, offline)
                                result.evidence.joinToString("\n") { evidence ->
                                    "${evidence.source}: ${evidence.status}" +
                                        listOfNotNull(evidence.name, evidence.province, evidence.city, evidence.carrier,
                                            evidence.category, evidence.company, evidence.info,
                                            evidence.risk.takeIf { it != "unknown" }).joinToString(" · ", prefix = "\n")
                                } + if (result.conflict) "\n" + ctx.getString(R.string.cn_risk_conflict) else ""
                            }
                            message = output
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { message = ctx.getString(R.string.cn_query_failed) }
                        finally { busy = false }
                    }
                }
            }
            StrokeButton(Str(R.string.cn_clear_cache), color = G.palette.textGrey, enabled = !busy) {
                busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            NumberIdentityCache(ctx).clear()
                            PhoneNumberMarkedDatabase.clearApiCache(ctx)
                        }
                        message = ctx.getString(R.string.cn_cache_cleared)
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { message = ctx.getString(R.string.cn_query_failed) }
                    finally { busy = false }
                }
            }
        }
        if (message.isNotBlank()) GreyText(message)
    }
}
