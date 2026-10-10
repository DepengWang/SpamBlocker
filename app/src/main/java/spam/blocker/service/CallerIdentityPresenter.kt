package spam.blocker.service

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.*
import spam.blocker.R
import spam.blocker.db.CnNumberHistory
import spam.blocker.ui.setting.quick.showCallerIdWindow
import spam.blocker.util.*

object CallerIdentityPresenter {
    fun launch(ctx: Context, raw: String, cnap: String?, reason: String?, token: Long) {
        val job = CoroutineScope(Dispatchers.IO).launch(start = CoroutineStart.LAZY) {
            try { show(ctx.applicationContext, raw, cnap, reason, token) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Never affect screening or post-screening workflows. */ }
            finally { CallerIdentitySession.finish(token) }
        }
        CallerIdentitySession.track(token, job)
        job.start()
    }
    suspend fun show(ctx: Context, raw: String, cnap: String?, reason: String?, token: Long) {
        val prefs = spf.CallerID(ctx)
        if (!prefs.isEnabled || !CallerIdentitySession.active(token)) return
        val contact = Contacts.findContactByRawNumber(ctx, raw)
        if (contact != null && !prefs.showForContacts) {
            withContext(Dispatchers.Main) {
                if (CallerIdentitySession.active(token)) spam.blocker.ui.widgets.FloatingWindow.hide(ctx)
            }
            return
        }
        val (offline, evidence) = IdentityLookup.offline(ctx, raw)
        val name = contact?.name ?: cnap
        suspend fun display(result: NumberIdentity, refresh: Boolean) {
            val mark = withContext(Dispatchers.IO) { CnNumberHistory.mark(ctx, result.number) }
            withContext(Dispatchers.Main) {
                if (!CallerIdentitySession.active(token) || !spf.CallerID(ctx).isEnabled) return@withContext
                val location = listOfNotNull(result.province, result.city).distinct().joinToString(" ").ifBlank { offline.location }
                val risk = when {
                    result.conflict -> ctx.getString(R.string.cn_risk_conflict)
                    result.risk == "high" -> ctx.getString(R.string.cn_risk_high)
                    result.risk == "medium" -> ctx.getString(R.string.cn_risk_medium)
                    else -> null // low or no mark is never a promise of safety
                }
                val sources = result.evidence.filter { it.status == "ok" && it.hasData() }
                    .map { it.source }.distinct().joinToString(" / ").takeIf { it.isNotBlank() }
                try {
                    showCallerIdWindow(ctx, reason, raw, name ?: result.name, location, result.carrier,
                        result.categories.joinToString(" / ").takeIf { it.isNotBlank() },
                        sources?.let { ctx.getString(R.string.cn_source_prefix, it) }, risk, refresh,
                        mark = mark, company = result.company, info = result.info)
                } catch (_: android.view.WindowManager.BadTokenException) { CallerIdentitySession.invalidate() }
                catch (_: SecurityException) { CallerIdentitySession.invalidate() }
            }
        }
        display(NumberIdentity.merge(offline.normalizedNumber ?: raw, evidence, emptyList()), false)
        // Do not upload address-book numbers by default. Normalize once for all adapters/cache keys.
        val normalized = offline.normalizedNumber ?: return
        if (contact != null || !CallerIdentitySession.active(token)) return
        val result = try { IdentityLookup.online(ctx.applicationContext, normalized, evidence) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { return } // cache/database failures cannot affect call handling
        if (result.evidence.size > 1) display(result, true)
    }
}
