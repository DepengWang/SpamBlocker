package spam.blocker.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import java.security.MessageDigest

@Serializable
data class IdentitySource(
    val id: String,
    val name: String,
    val enabled: Boolean = false,
    // Optional legacy PhoneNumber INumber API_ID used with the api_type preference.
    val apiId: Int? = null,
    val url: String = "",
    val method: String = "GET",
    // Arbitrary legacy/custom query parameters, e.g. tel={domestic}, key={shared_pref(baidu_api_key)}.
    val parameters: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
    // Dot paths support object keys and numeric array indexes.
    val paths: Map<String, String> = mapOf("name" to "name", "category" to "category", "risk" to "riskLevel"),
    val riskMapping: Map<String, String> = emptyMap(),
    // A successful HTTP response alone is not proof of a successful API lookup.
    val successPath: String = "",
    val successValue: String = "",
) {
    fun fingerprint(): String {
        val config = Json.encodeToString(serializer(), this) +
            if (id == "tmini-checkmark") "|tmini-parser-v2" else ""
        return MessageDigest.getInstance("SHA-256")
        .digest(config.toByteArray())
        .joinToString("") { "%02x".format(it) }
    }
}

/** Safe, user-visible API failure detail. Never includes request URLs or credentials. */
class IdentityApiException(message: String) : Exception(message)

/** Maps transport failures to actionable, credential-safe diagnostics. Never expose raw exception text. */
internal object IdentityNetworkError {
    fun describe(error: Throwable): String {
        val causes = generateSequence(error) { it.cause }
            .take(8)
            .toList()
        val message = causes.mapNotNull { it.message }.joinToString(" ").lowercase()
        return when {
            causes.any { it is java.net.UnknownHostException } -> "DNS 解析失败：无法解析接口主机"
            causes.any { it is java.net.SocketTimeoutException } -> "连接或读取超时；请检查网络后再试"
            causes.any { it is javax.net.ssl.SSLException || it is java.security.cert.CertificateException } ->
                "TLS 安全连接失败；请检查设备时间、证书或网络代理"
            causes.any { it is java.net.NoRouteToHostException } || "network is unreachable" in message ->
                "网络不可达；请检查当前网络或 VPN"
            causes.any { it is java.net.ConnectException } && ("refused" in message || "拒绝" in message) ->
                "服务器拒绝连接；接口可能暂时不可用"
            causes.any { it is java.net.ConnectException } -> "无法连接接口服务器；请检查网络或服务状态"
            causes.any { it is java.net.SocketException } && ("reset" in message || "断开" in message) ->
                "连接被对端重置；接口或网络可能不稳定"
            causes.any { it is java.net.SocketException } && ("broken pipe" in message || "管道" in message) ->
                "连接中断；接口或网络可能不稳定"
            causes.any { it is java.net.SocketException } -> "网络连接中断（SocketException）"
            causes.any { it is java.io.IOException } -> "网络 I/O 失败；接口可能暂时不可用"
            else -> "请求失败（${error.javaClass.simpleName.take(48)}）"
        }
    }
}

@Serializable
data class IdentityEvidence(
    val sourceId: String,
    val source: String,
    val status: String = "ok", // ok, empty, error, timeout
    val name: String? = null,
    val province: String? = null,
    val city: String? = null,
    val carrier: String? = null,
    val category: String? = null,
    val risk: String = "unknown", // unknown, low, medium, high
    val queriedAt: Long = 0,
    val company: String? = null,
    val info: String? = null,
) {
    fun hasData() = listOf(name, province, city, carrier, category, company, info).any { !it.isNullOrBlank() } || risk != "unknown"
}

data class NumberIdentity(
    val number: String,
    val name: String? = null,
    val province: String? = null,
    val city: String? = null,
    val carrier: String? = null,
    val categories: List<String> = emptyList(),
    val risk: String = "unknown",
    val conflict: Boolean = false,
    val evidence: List<IdentityEvidence> = emptyList(),
    val company: String? = null,
    val info: String? = null,
) {
    companion object {
        // Input order is configured source order, never network completion order.
        fun merge(number: String, offline: IdentityEvidence, evidence: List<IdentityEvidence>): NumberIdentity {
            val valid = evidence.filter { it.status == "ok" }
            val usable = evidence.filter { it.status == "ok" || it.status == "empty" }
            fun first(field: (IdentityEvidence) -> String?) = usable.mapNotNull(field).firstOrNull { it.isNotBlank() }
            val risks = valid.map { it.risk }.filter { it != "unknown" }.distinct()
            val categories = (listOf(offline) + valid)
                .mapNotNull { it.category?.takeIf(String::isNotBlank) }.distinct()
            val names = valid.mapNotNull { it.name?.takeIf(String::isNotBlank) }.distinct()
            val conflict = risks.size > 1 || names.size > 1 || categories.size > 1
            return NumberIdentity(number, first { it.name }, offline.province ?: first { it.province },
                offline.city ?: first { it.city }, offline.carrier ?: first { it.carrier }, categories,
                if (conflict) "unknown" else risks.singleOrNull() ?: "unknown", conflict, listOf(offline) + evidence,
                first { it.company }, first { it.info })
        }
    }
}

object IdentityJson {
    val json = Json { ignoreUnknownKeys = true }

    private data class TminiRegion(val province: String?, val city: String?, val carrier: String?)

    private fun parseTminiRegion(value: String?): TminiRegion? {
        var text = value?.replace("中国", "")?.replace(Regex("\\s+"), "")?.trim()
            ?.takeIf(String::isNotEmpty) ?: return null
        val carriers = listOf(
            "移动" to "中国移动", "联通" to "中国联通", "电信" to "中国电信", "广电" to "中国广电",
        )
        val carrier = carriers.firstOrNull { (suffix, _) -> text.endsWith(suffix) }
        if (carrier != null) text = text.removeSuffix(carrier.first)
        val provinces = listOf(
            "内蒙古" to "内蒙古自治区", "广西" to "广西壮族自治区", "西藏" to "西藏自治区",
            "宁夏" to "宁夏回族自治区", "新疆" to "新疆维吾尔自治区", "北京" to "北京市",
            "上海" to "上海市", "天津" to "天津市", "重庆" to "重庆市", "香港" to "香港特别行政区",
            "澳门" to "澳门特别行政区", "黑龙江" to "黑龙江省", "吉林" to "吉林省", "辽宁" to "辽宁省",
            "河北" to "河北省", "山西" to "山西省", "江苏" to "江苏省", "浙江" to "浙江省",
            "安徽" to "安徽省", "福建" to "福建省", "江西" to "江西省", "山东" to "山东省",
            "河南" to "河南省", "湖北" to "湖北省", "湖南" to "湖南省", "广东" to "广东省",
            "海南" to "海南省", "四川" to "四川省", "贵州" to "贵州省", "云南" to "云南省",
            "陕西" to "陕西省", "甘肃" to "甘肃省", "青海" to "青海省", "台湾" to "台湾省",
        ).sortedByDescending { it.first.length }
        val province = provinces.firstOrNull { (prefix, _) -> text.startsWith(prefix) } ?: return null
        val remainder = text.removePrefix(province.first).removePrefix("省").removePrefix("市")
            .removePrefix("壮族自治区").removePrefix("维吾尔自治区").removePrefix("回族自治区")
            .removePrefix("自治区").removePrefix("特别行政区")
            .removeSuffix("省").removeSuffix("市").removeSuffix("州").removeSuffix("地区")
        val city = remainder.takeIf { it.isNotBlank() }?.let { if (it.endsWith("市") || it.endsWith("州") || it.endsWith("地区")) it else "${it}市" }
        return TminiRegion(province.second, city, carrier?.second)
    }

    fun sources(value: String): List<IdentitySource> {
        val sources = json.decodeFromString<List<IdentitySource>>(value)
        require(sources.size <= 6 && sources.map { it.id }.distinct().size == sources.size)
        sources.forEach {
            require(it.id.isNotBlank() && it.name.isNotBlank())
            require(it.method in listOf("GET", "POST"))
            require(!it.enabled || it.url.startsWith("https://"))
            require(it.headers.keys.none { key -> key.contains('\n') || key.contains('\r') })
            require(it.headers.values.none { header -> header.contains('\n') || header.contains('\r') })
            require(it.parameters.keys.none { key -> key.contains('\n') || key.contains('\r') || key.isBlank() })
            require(it.parameters.values.none { value -> value.contains('\n') || value.contains('\r') })
        }
        return sources
    }

    fun parse(source: IdentitySource, response: String, now: Long): IdentityEvidence {
        val root = json.parseToJsonElement(response)
        fun elementsAt(path: String): List<JsonElement> {
            if (path.isBlank()) return emptyList()
            var values = listOf(root)
            for (key in path.split('.')) {
                values = values.flatMap { value -> when {
                    value is JsonObject -> listOfNotNull(value[key])
                    value is JsonArray && key == "*" -> value.toList()
                    value is JsonArray -> listOfNotNull(value.getOrNull(key.toIntOrNull() ?: return@flatMap emptyList()))
                    else -> emptyList()
                } }
                if (values.isEmpty()) return emptyList()
            }
            return values
        }
        fun elementAt(path: String): JsonElement? = elementsAt(path).firstOrNull()
        fun at(path: String): String? = (elementAt(path) as? JsonPrimitive)?.contentOrNull
            ?.trim()?.takeIf { it.isNotEmpty() }?.take(160)
        val legacyStatus = at("error_code")
        if (source.successPath.isNotBlank()) {
            val value = at(source.successPath)
            if (value !in source.successValue.split('|')) {
                val detail = at("message") ?: at("msg") ?: at("error")
                throw IdentityApiException(buildString {
                    append("接口业务码 ${source.successPath}=${value ?: "缺失"}")
                    if (detail != null) append("：").append(detail)
                })
            }
        } else if (legacyStatus != null && legacyStatus != "0") {
            val detail = at("message") ?: at("msg") ?: at("error")
            throw IdentityApiException("接口错误码 $legacyStatus" + (detail?.let { "：$it" } ?: ""))
        }
        fun field(key: String): String? {
            val configured = source.paths[key]
            if (!configured.isNullOrBlank()) {
                for (candidatePath in configured.split('|')) {
                    val configuredValues = elementsAt(candidatePath).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        .map(String::trim).filter(String::isNotEmpty).distinct().take(8)
                    if (configuredValues.isNotEmpty()) {
                        return if ('*' in candidatePath) configuredValues.joinToString(" / ").take(160)
                            else configuredValues.first().take(160)
                    }
                }
            }
            val legacyPath = when (key) {
                "carrier" -> "result.provider"
                "company" -> "result.company"
                "info" -> "result.info"
                "name" -> "result.name"
                "province" -> "result.province"
                "city" -> "result.city"
                else -> ""
            }
            return at(legacyPath)
        }
        // An unexpected schema is an error, not a cached negative result.
        if (!(source.paths.values.any { path -> path.split('|').any { candidate -> elementsAt(candidate).any { it is JsonPrimitive } } }
                || source.successPath.isNotBlank() || legacyStatus != null)) {
            throw IdentityApiException("响应 JSON 缺少预期字段")
        }
        val rawRisk = field("risk")
        val risk = source.riskMapping[rawRisk] ?: rawRisk
        val rawProvince = field("province")
        val tminiRegion = if (source.id == "tmini-checkmark") parseTminiRegion(rawProvince) else null
        val evidence = IdentityEvidence(source.id, source.name, name = field("name"),
            province = tminiRegion?.province ?: rawProvince,
            city = field("city") ?: tminiRegion?.city, carrier = field("carrier") ?: tminiRegion?.carrier,
            category = field("category"),
            risk = risk?.takeIf { it in listOf("low", "medium", "high") } ?: "unknown", queriedAt = now,
            company = field("company"), info = field("info"))
        val confirmedNoMark = source.id == "tmini-checkmark" && at("isMarked") == "false" &&
            evidence.category.isNullOrBlank() && evidence.name.isNullOrBlank() && evidence.company.isNullOrBlank()
        return if (confirmedNoMark || !evidence.hasData()) evidence.copy(status = "empty") else evidence
    }
}

@Serializable
data class IdentityCachePolicy(val enterpriseHours: Int = 168, val markHours: Int = 24,
    val emptyHours: Int = 6, val errorMinutes: Int = 2) {
    fun ttlMillis(evidence: IdentityEvidence): Long = when (evidence.status) {
        "error", "timeout" -> errorMinutes.coerceIn(1, 60) * 60_000L
        "empty" -> emptyHours.coerceIn(1, 168) * 3_600_000L
        else -> {
            val identity = enterpriseHours.coerceIn(1, 720)
            val mark = markHours.coerceIn(1, 720)
            val marked = !evidence.category.isNullOrBlank() || evidence.risk != "unknown"
            (if (marked && !evidence.name.isNullOrBlank()) minOf(identity, mark)
            else if (marked) mark else identity) * 3_600_000L
        }
    }
}

interface IdentityCache {
    fun get(number: String, key: String, now: Long): IdentityEvidence?
    fun put(number: String, key: String, evidence: IdentityEvidence, expiresAt: Long)
}

class IdentityQueryEngine(private val cache: IdentityCache,
    private val clock: () -> Long = System::currentTimeMillis,
    private val fetch: suspend (IdentitySource, String) -> String) {
    companion object { private val permits = Semaphore(3) }
    suspend fun query(number: String, offline: IdentityEvidence, sources: List<IdentitySource>,
        policy: IdentityCachePolicy = IdentityCachePolicy(), timeoutMs: Long = 3000): NumberIdentity = coroutineScope {
        val evidence = sources.filter { it.enabled }.take(6).map { source -> async {
            val key = source.fingerprint()
            runCatching { cache.get(number, key, clock()) }.getOrNull() ?: run {
                val result = try {
                    withTimeoutOrNull(timeoutMs.coerceIn(250, 5000)) {
                        permits.withPermit { IdentityJson.parse(source, fetch(source, number), clock()) }
                } ?: IdentityEvidence(source.id, source.name, "timeout", queriedAt = clock(), info = "请求超时")
                } catch (e: CancellationException) { throw e }
            catch (e: IdentityApiException) {
                IdentityEvidence(source.id, source.name, "error", queriedAt = clock(), info = e.message?.take(160))
            }
            catch (_: SerializationException) {
                IdentityEvidence(source.id, source.name, "error", queriedAt = clock(), info = "响应不是有效 JSON")
            }
            catch (e: Exception) {
                IdentityEvidence(source.id, source.name, "error", queriedAt = clock(),
                    info = IdentityNetworkError.describe(e))
            }
                coroutineContext.ensureActive()
                runCatching { cache.put(number, key, result, clock() + policy.ttlMillis(result)) }
                result
            }
        } }.awaitAll()
        NumberIdentity.merge(number, offline, evidence)
    }
}
