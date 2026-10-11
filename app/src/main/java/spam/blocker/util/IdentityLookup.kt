package spam.blocker.util

import android.content.Context
import kotlinx.coroutines.*
import spam.blocker.db.NumberIdentityCache
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object IdentityLookup {
    fun offline(ctx: Context, raw: String): Pair<CnNumberLookup.Result, IdentityEvidence> {
        val result = CnNumberLookup.lookupOffline(ctx, raw)
        // This legacy dataset only covers mainland numbers. Keep its category informational;
        // it must not be interpreted as a current risk verdict or an automatic block rule.
        val mark = if (result.countryCode == 86) {
            PhoneNumberMarkedDatabase.find(ctx, raw, result.nationalNumber)
        } else null
        val cached = if (result.countryCode == 86 && mark == null)
            PhoneNumberMarkedDatabase.cachedApiResult(ctx, raw, result.nationalNumber) else null
        val sources = listOfNotNull(result.source, mark?.let { PhoneNumberMarkedDatabase.SOURCE_NAME },
            cached?.let { "Tmini 本地缓存" })
            .joinToString(" + ")
        return result to IdentityEvidence("offline", sources.ifBlank { "offline" },
            province = result.province ?: cached?.province, city = result.city ?: result.location ?: cached?.city,
            carrier = result.carrier ?: cached?.carrier, category = mark ?: cached?.category,
            company = cached?.company, info = cached?.info)
    }

    suspend fun online(ctx: Context, number: String, offline: IdentityEvidence,
        timeoutMs: Int? = null, retryFailures: Boolean = false): NumberIdentity {
        val prefs = spf.ApiQueryOptions(ctx)
        // Interactive diagnostics may wait longer without changing the automatic caller query budget.
        val budget = timeoutMs?.coerceIn(250, 30_000) ?: prefs.identityTimeoutMs.coerceIn(250, 5000)
        val normalized = CnNumberLookup.normalizeNumber(number)
        val domestic = normalized?.nationalNumber ?: number
        val ignoredPrefixes = prefs.ignoredNumberPrefixes
            .split(',', ';', '\n', '\r', ' ', '\t').map(String::trim).filter(String::isNotEmpty)
        val ignored = ignoredPrefixes.any { prefix -> number.startsWith(prefix) || domestic.startsWith(prefix) }
        if (prefs.onlyOffline || ignored) return NumberIdentity.merge(number, offline, emptyList())
        val configuredSources = runCatching { IdentityJson.sources(prefs.identitySources) }.getOrDefault(emptyList())
        val localMark = PhoneNumberMarkedDatabase.find(ctx, number, domestic)
        val tminiCached = PhoneNumberMarkedDatabase.cachedApiResult(ctx, number, domestic)
        val eligibleSources = configuredSources.filterNot { source ->
            source.id == "tmini-checkmark" && (localMark != null || tminiCached != null)
        }
        val sources = if (prefs.apiType == -1) eligibleSources
            else eligibleSources.sortedBy { if (it.apiId == prefs.apiType) 0 else 1 }
        val policy = runCatching { IdentityJson.json.decodeFromString<IdentityCachePolicy>(prefs.identityCachePolicy) }
            .getOrDefault(IdentityCachePolicy())
        val identity = IdentityQueryEngine(NumberIdentityCache(ctx)) { source, normalized ->
            val resolved = source.copy(
                headers = source.headers.mapValues { it.value.resolveSpfTag(ctx).resolveBase64Tag() },
                parameters = source.parameters.mapValues { it.value.resolveSpfTag(ctx).resolveBase64Tag() },
                url = source.url.resolveSpfTag(ctx).resolveBase64Tag(),
            )
            request(resolved, normalized, budget)
        }.query(number, offline, sources, policy, budget.toLong(), retryFailures)
        identity.evidence.filter { it.sourceId == "tmini-checkmark" && it.status in listOf("ok", "empty") }
            .forEach { evidence ->
                PhoneNumberMarkedDatabase.saveApiResult(ctx, number, domestic, evidence)
                PhoneNumberMarkedDatabase.saveRegionOverlayIfMissing(ctx, number, domestic, offline, evidence)
            }
        return identity
    }

    // No logs, retries, redirects, raw response persistence, or credentials in cache keys.
    // Blocking network IO is detached from the screening response and cancelled via disconnect.
    internal suspend fun request(source: IdentitySource, number: String, timeoutMs: Int = 3000): String = suspendCancellableCoroutine { continuation ->
        val connection = AtomicReference<HttpURLConnection?>()
        val job = CoroutineScope(Dispatchers.IO).launch {
            var stage = "prepare"
            try {
                val encoded = URLEncoder.encode(number, "UTF-8")
                val domestic = URLEncoder.encode(CnNumberLookup.normalizeNumber(number)?.nationalNumber ?: number, "UTF-8")
                val baseUrl = source.url.replace("{number}", encoded).replace("{domestic}", domestic)
                val uriBuilder = android.net.Uri.parse(baseUrl).buildUpon()
                source.parameters.forEach { (key, value) ->
                    uriBuilder.appendQueryParameter(key, value.replace("{number}", number)
                        .replace("{domestic}", CnNumberLookup.normalizeNumber(number)?.nationalNumber ?: number))
                }
                val parsed = URL(uriBuilder.build().toString())
                require(parsed.protocol == "https" && parsed.userInfo == null)
                val conn = parsed.openConnection() as HttpURLConnection
                connection.set(conn)
                if (!continuation.isActive) { conn.disconnect(); return@launch }
                conn.connectTimeout = timeoutMs.coerceIn(250, 30_000)
                conn.readTimeout = timeoutMs.coerceIn(250, 30_000)
                conn.instanceFollowRedirects = false
                conn.requestMethod = source.method
                conn.setRequestProperty("User-Agent", "SpamBlocker/${spam.blocker.BuildConfig.VERSION_NAME}")
                source.headers.forEach { (key, value) -> conn.setRequestProperty(key, value) }
                if (source.method == "POST") conn.doOutput = true
                stage = "connect"
                conn.connect()
                if (source.method == "POST") {
                    stage = "request"
                    conn.outputStream.use { it.write(source.body.replace("{number}", number)
                        .replace("{domestic}", CnNumberLookup.normalizeNumber(number)?.nationalNumber ?: number).toByteArray()) }
                }
                stage = "response"
                val status = conn.responseCode
                if (status !in 200..299) throw IdentityApiException("HTTP $status")
                stage = "read"
                val bytes = conn.inputStream.use { it.readNBytesCompat(256 * 1024) }
                if (continuation.isActive) continuation.resume(bytes.toString(Charsets.UTF_8))
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(
                    if (e is IdentityApiException) e else IdentityRequestException(stage, e))
            } finally { connection.getAndSet(null)?.disconnect() }
        }
        continuation.invokeOnCancellation { connection.getAndSet(null)?.disconnect(); job.cancel() }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
