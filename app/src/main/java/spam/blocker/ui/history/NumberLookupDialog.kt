package spam.blocker.ui.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import spam.blocker.G
import spam.blocker.R
import spam.blocker.db.*
import spam.blocker.def.Def
import spam.blocker.ui.setting.LabeledRow
import spam.blocker.ui.M
import spam.blocker.ui.widgets.*
import spam.blocker.util.*

@Composable
fun NumberLookupDialog(trigger: MutableState<Boolean>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lookupHistory = remember { mutableStateListOf<CnNumberLookupRecord>() }
    val savedMarks = remember { mutableStateMapOf<String, String>() }
    var number by remember { mutableStateOf("") }
    var current by remember { mutableStateOf<NumberIdentity?>(null) }
    var inputError by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var markText by remember { mutableStateOf("") }
    var queryJob by remember { mutableStateOf<Job?>(null) }
    suspend fun reloadHistory() {
        val records = withContext(Dispatchers.IO) { CnNumberHistory.list(ctx, 12) }
        val marks = withContext(Dispatchers.IO) { records.mapNotNull { row -> CnNumberHistory.mark(ctx, row.number)?.let { row.number to it } }.toMap() }
        lookupHistory.apply { clear(); addAll(records) }
        savedMarks.clear(); savedMarks.putAll(marks)
    }
    fun addExactRule(identity: NumberIdentity, blacklist: Boolean) {
        val national = CnNumberLookup.normalizeNumber(identity.number)?.nationalNumber
        if (national.isNullOrBlank()) { note = ctx.getString(R.string.cn_invalid_number); return }
        scope.launch {
            val added = withContext(Dispatchers.IO) {
                val table = NumberRegexTable()
                val flags = Def.FLAG_REGEX_IGNORE_CC
                val pattern = Regex.escape(national)
                if (table.listAll(ctx).any { it.pattern == pattern && it.patternFlags == flags }) false
                else {
                    table.addNew(ctx, RegexRule(pattern = pattern, patternFlags = flags,
                        description = ctx.getString(if (blacklist) R.string.cn_manual_block_rule else R.string.cn_manual_allow_rule),
                        priority = 100, isBlacklist = blacklist, flags = Def.FLAG_FOR_CALL,
                        channel = if (blacklist) Def.DEF_SPAM_CHANNEL else spam.blocker.db.Notification.CHANNEL_HIGH))
                    true
                }
            }
            if (added) G.NumberRuleVM.reloadDbAndOptions(ctx)
            note = ctx.getString(if (!added) R.string.cn_rule_exists else if (blacklist) R.string.cn_rule_block_added else R.string.cn_rule_allow_added)
        }
    }
    fun doQuery() {
        val parsed = CnNumberLookup.normalizeNumber(number)
        if (parsed == null) { inputError = ctx.getString(R.string.cn_invalid_number); return }
        inputError = ""; note = ""; current = null; busy = true; queryJob?.cancel()
        queryJob = scope.launch {
            try {
                val normalized = parsed.e164
                val (offlineResult, offlineEvidence) = withContext(Dispatchers.IO) { IdentityLookup.offline(ctx, normalized) }
                val key = offlineResult.normalizedNumber ?: normalized
                current = NumberIdentity.merge(key, offlineEvidence, emptyList())
                markText = withContext(Dispatchers.IO) { CnNumberHistory.mark(ctx, key).orEmpty() }
                current = withContext(Dispatchers.IO) { IdentityLookup.online(ctx.applicationContext, key, offlineEvidence) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { note = ctx.getString(R.string.cn_query_failed) }
            finally { busy = false }
        }
    }
    LaunchedEffect(trigger.value) { if (trigger.value) reloadHistory() }

    PopupDialog(trigger, popupSize = PopupSize(maxWidthPercentage = 0.94f, maxWidthDp = 560),
        onDismiss = { queryJob?.cancel(); busy = false },
        buttons = {
            if (current != null) {
                StrokeButton(Str(R.string.cn_save_lookup), color = G.palette.textGrey, enabled = !busy) {
                    val snapshot = current!!
                    scope.launch(Dispatchers.IO) {
                        CnNumberHistory.save(ctx, snapshot.number, snapshot.evidence)
                        withContext(Dispatchers.Main) { reloadHistory(); note = ctx.getString(R.string.cn_lookup_saved) }
                    }
                }
            }
        }) {
        GreyText(Str(R.string.cn_lookup_help))
        Row {
            StrInputBox(number, { number = it; inputError = "" },
                label = { GreyLabel(Str(R.string.cn_manual_number)) }, singleLine = true,
                modifier = M.weight(1f))
            StrokeButton(Str(R.string.cn_query), color = G.palette.textGrey, enabled = !busy) { doQuery() }
        }
        if (inputError.isNotBlank()) GreyText(inputError)
        if (busy) GreyText(Str(R.string.cn_lookup_loading))
        current?.let { identity ->
            val location = listOfNotNull(identity.province, identity.city).distinct().joinToString(" ")
            listOfNotNull(identity.name, location.ifBlank { null }, identity.carrier,
                identity.company, identity.info,
                identity.categories.takeIf { it.isNotEmpty() }?.joinToString(" / "),
                when {
                    identity.conflict -> ctx.getString(R.string.cn_risk_conflict)
                    identity.risk == "high" -> ctx.getString(R.string.cn_risk_high)
                    identity.risk == "medium" -> ctx.getString(R.string.cn_risk_medium)
                    else -> null
                }, markText.takeIf { it.isNotBlank() })
                .forEach { GreyText(it) }
            if (identity.evidence.none { it.status == "ok" && it.hasData() }) GreyText(Str(R.string.cn_no_identity))
            LabeledRow(R.string.cn_custom_mark) {
                StrInputBox(markText, { markText = it }, label = { GreyLabel(Str(R.string.cn_mark_name)) }, singleLine = true)
                StrokeButton(Str(R.string.cn_save_mark), color = G.palette.textGrey, enabled = markText.isNotBlank()) {
                    val label = markText
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        CnNumberHistory.setMark(ctx, identity.number, label)
                        withContext(Dispatchers.Main) { busy = false; note = ctx.getString(R.string.cn_mark_saved) }
                    }
                }
            }
            Row {
                StrokeButton(Str(R.string.cn_allow_number), color = G.palette.success) { addExactRule(identity, false) }
                StrokeButton(Str(R.string.cn_block_number), color = G.palette.error) { addExactRule(identity, true) }
            }
        }
        if (note.isNotBlank()) GreyText(note)
        if (lookupHistory.isNotEmpty()) {
            GreyText(Str(R.string.cn_recent_lookups))
            lookupHistory.forEach { saved ->
                Column {
                    val evidence = saved.evidence()
                    val identity = NumberIdentity.merge(saved.number,
                        evidence.firstOrNull { it.sourceId == "offline" } ?: IdentityEvidence("offline", "offline"),
                        evidence.filterNot { it.sourceId == "offline" })
                    val summary = listOfNotNull(identity.name,
                        listOfNotNull(identity.province, identity.city).distinct().joinToString(" ").ifBlank { null },
                        identity.carrier, savedMarks[saved.number],
                        identity.company, identity.info,
                        identity.categories.takeIf { it.isNotEmpty() }?.joinToString(" / "),
                        identity.evidence.filter { it.status == "ok" }.map { it.source }.distinct().joinToString(" / ").ifBlank { null })
                        .joinToString(" · ")
                    RowVCenterSpaced(4) {
                        StrokeButton(saved.number, color = G.palette.textGrey) {
                            number = saved.number
                            current = identity
                            markText = savedMarks[saved.number].orEmpty()
                            note = ""
                        }
                        StrokeButton("×", color = G.palette.error) {
                            scope.launch(Dispatchers.IO) {
                                CnNumberHistory.delete(ctx, saved.id)
                                withContext(Dispatchers.Main) { reloadHistory() }
                            }
                        }
                    }
                    if (summary.isNotBlank()) GreyText(summary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    GreyText(android.text.format.DateFormat.getMediumDateFormat(ctx).format(saved.lookedUpAt) + " " +
                        android.text.format.DateFormat.getTimeFormat(ctx).format(saved.lookedUpAt))
                }
            }
        }
    }

}
