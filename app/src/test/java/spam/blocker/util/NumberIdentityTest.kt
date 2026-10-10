package spam.blocker.util

import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class NumberIdentityTest {
    private val offline = IdentityEvidence("offline", "local", province = "江苏省", city = "无锡市", carrier = "中国电信")
    private fun source(id: String = "a") = IdentitySource(id, id, enabled = true,
        url = "https://example.com/?number={number}", successPath = "code", successValue = "0",
        paths = mapOf("name" to "data.name", "category" to "data.category", "risk" to "data.risk"))
    private class Cache : IdentityCache {
        val values = ConcurrentHashMap<String, Pair<IdentityEvidence, Long>>()
        override fun get(number: String, key: String, now: Long) = values["$number:$key"]?.takeIf { it.second > now }?.first
        override fun put(number: String, key: String, evidence: IdentityEvidence, expiresAt: Long) {
            values["$number:$key"] = evidence to expiresAt
        }
    }
    @Test fun `nested JSON extracts identity and maps risk`() {
        val result = IdentityJson.parse(source().copy(paths = mapOf("name" to "data.0.title", "risk" to "data.0.level"),
            riskMapping = mapOf("3" to "high")), """{"code":0,"data":[{"title":"银行","level":3}]}""", 123)
        assertEquals("银行", result.name); assertEquals("high", result.risk); assertEquals(123L, result.queriedAt)
    }
    @Test fun `Tmini aggregate response collects tags across platforms`() {
        val tmini = IdentitySource("tmini-checkmark", "Tmini 聚合号码标记", enabled = true,
            url = "https://api.tmini.net/apis/checkmark",
            parameters = mapOf("key" to "{shared_pref(tmini_api_key)}", "phone" to "{domestic}"),
            paths = mapOf("province" to "region", "company" to "certName",
                "category" to "results.*.tags.*.name"), successPath = "code", successValue = "200")
        val marked = IdentityJson.parse(tmini,
            """{"code":200,"phone":"02131216055","isMarked":true,"region":"上海","results":[{"platform":"腾讯手机管家","status":"未标记"},{"platform":"泰迪熊","status":"被标记","tags":[{"name":"骚扰电话","count":19}]},{"platform":"电话邦","status":"被标记","tags":[{"name":"营销推广","count":184}]},{"platform":"百度","status":"被标记","tags":[{"name":"广告营销"}]}]}""", 1)
        assertEquals("上海市", marked.province)
        assertEquals("骚扰电话 / 营销推广 / 广告营销", marked.category)
        val unmarked = IdentityJson.parse(tmini,
            """{"code":200,"phone":"13800138000","isMarked":false,"region":"北京","results":[{"platform":"腾讯手机管家","status":"未标记"},{"platform":"360手机卫士","status":"未标记"}]}""", 2)
        assertEquals("empty", unmarked.status)
        assertEquals("unknown", unmarked.risk)
        assertEquals("北京市", NumberIdentity.merge("13800138000", offline.copy(province = null), listOf(unmarked)).province)
        val parsedRegion = IdentityJson.parse(tmini,
            """{"code":200,"isMarked":true,"region":"江苏无锡 移动","results":[]}""", 3)
        assertEquals("江苏省", parsedRegion.province)
        assertEquals("无锡市", parsedRegion.city)
        assertEquals("中国移动", parsedRegion.carrier)
        assertEquals(6 * 3_600_000L, IdentityCachePolicy().ttlMillis(unmarked))
        val error = assertThrows(IdentityApiException::class.java) {
            IdentityJson.parse(tmini, """{"code":0,"errcode":11004,"msg":"积分余额不足"}""", 3)
        }
        assertTrue(error.message.orEmpty().contains("积分余额不足"))
    }
    @Test fun `API business error and schema error are not no result`() {
        val businessError = assertThrows(IdentityApiException::class.java) {
            IdentityJson.parse(source(), """{"code":401,"message":"unauthorized"}""", 0)
        }
        assertTrue(businessError.message.orEmpty().contains("401"))
        assertTrue(businessError.message.orEmpty().contains("unauthorized"))
        assertThrows(IdentityApiException::class.java) {
            IdentityJson.parse(source().copy(successPath = ""), """{"unexpected":"error"}""", 0)
        }
    }
    @Test fun `network diagnostics classify unstable connections without exposing exception details`() {
        assertEquals("DNS 解析失败：无法解析接口主机",
            IdentityNetworkError.describe(java.net.UnknownHostException("api.example.com")))
        assertEquals("连接或读取超时；请检查网络后再试",
            IdentityNetworkError.describe(java.net.SocketTimeoutException("read timed out")))
        val reset = IdentityNetworkError.describe(java.net.SocketException("Connection reset; key=secret"))
        assertEquals("连接被对端重置；接口或网络可能不稳定", reset)
        assertFalse(reset.contains("secret"))
        assertTrue(IdentityNetworkError.describe(javax.net.ssl.SSLHandshakeException("bad certificate"))
            .contains("TLS 安全连接失败"))
    }
    @Test fun `confirmed no result stays unknown not safe`() {
        val result = IdentityJson.parse(source(), """{"code":0,"data":{}}""", 0)
        assertEquals("empty", result.status); assertEquals("unknown", NumberIdentity.merge("n", offline, listOf(result)).risk)
        assertEquals("empty", IdentityJson.parse(source().copy(successPath = ""),
            """{"data":{"name":null,"category":null,"risk":null}}""", 0).status)
    }
    @Test fun `unrecognized risk cannot imply safety`() {
        assertEquals("unknown", IdentityJson.parse(source(), """{"code":0,"data":{"risk":"safe","category":"unknown"}}""", 0).risk)
    }
    @Test fun `offline geography cannot be overwritten by API`() {
        val api = IdentityEvidence("a", "api", province = "其他省", city = "其他市", carrier = "其他运营商", name = "企业")
        val result = NumberIdentity.merge("n", offline, listOf(api))
        assertEquals("无锡市", result.city); assertEquals("中国电信", result.carrier); assertEquals("企业", result.name)
        assertEquals(2, result.evidence.size)
    }
    @Test fun `Tmini geography fills only missing libphonenumber fields`() {
        val local = CnNumberLookup.Result(rawNumber = "13921198124", normalizedNumber = "+8613921198124",
            countryCode = 86, province = "江苏省", city = null, carrier = "中国移动", source = "libphonenumber-offline",
            databaseVersion = "libphonenumber-9.0.39")
        val tmini = IdentityEvidence("tmini-checkmark", "Tmini", province = "江苏省", city = "无锡市",
            carrier = "中国联通")
        val merged = CnNumberLookup.applyTminiFallback(local, tmini)
        assertEquals("江苏省", merged.province)
        assertEquals("无锡市", merged.city)
        assertEquals("中国移动", merged.carrier)
        assertTrue(merged.source.orEmpty().contains("Tmini-cache"))
    }
    @Test fun `offline marked database category is included in identity`() {
        val marked = offline.copy(source = "local + PhoneNumber marked.db", category = "快递送餐")
        val result = NumberIdentity.merge("n", marked, emptyList())
        assertEquals(listOf("快递送餐"), result.categories)
        assertEquals("unknown", result.risk)
    }
    @Test fun `conflicts retained and do not become safety verdict`() {
        val result = NumberIdentity.merge("n", offline, listOf(IdentityEvidence("a", "A", category = "广告", risk = "high"),
            IdentityEvidence("b", "B", category = "银行", risk = "low")))
        assertTrue(result.conflict); assertEquals("unknown", result.risk); assertEquals(listOf("广告", "银行"), result.categories)
    }
    @Test fun `configuration bounds reject HTTP duplicate ids and excessive sources`() {
        fun encoded(sources: List<IdentitySource>) = IdentityJson.json.encodeToString(kotlinx.serialization.builtins.ListSerializer(IdentitySource.serializer()), sources)
        assertThrows(IllegalArgumentException::class.java) { IdentityJson.sources(encoded(listOf(source().copy(url = "http://example.com")))) }
        assertThrows(IllegalArgumentException::class.java) { IdentityJson.sources(encoded(listOf(source(), source()))) }
        assertThrows(IllegalArgumentException::class.java) { IdentityJson.sources(encoded((0..6).map { source("$it") })) }
        assertTrue(IdentityJson.sources("[]").isEmpty())
    }
    @Test fun `cache hit suppresses requests and expires`() = runBlocking {
        var now = 100L; val count = AtomicInteger()
        val engine = IdentityQueryEngine(Cache(), { now }) { _, _ -> count.incrementAndGet(); """{"code":0,"data":{"name":"企业"}}""" }
        repeat(2) { engine.query("n", offline, listOf(source())) }; assertEquals(1, count.get())
        now += 168 * 3_600_000L; engine.query("n", offline, listOf(source())); assertEquals(2, count.get())
    }
    @Test fun `cache separates source configuration and numbers`() = runBlocking {
        val count = AtomicInteger()
        val engine = IdentityQueryEngine(Cache(), { 0 }) { _, _ -> count.incrementAndGet(); """{"code":0}""" }
        engine.query("n", offline, listOf(source()))
        engine.query("n", offline, listOf(source().copy(url = "https://example.com/changed")))
        engine.query("other", offline, listOf(source())); assertEquals(3, count.get())
    }
    @Test fun `one failure does not discard other sources and configured order wins`() = runBlocking {
        val engine = IdentityQueryEngine(Cache()) { src, _ -> when (src.id) {
            "bad" -> error("failure")
            "slow" -> { delay(30); """{"code":0,"data":{"name":"first"}}""" }
            else -> """{"code":0,"data":{"name":"second"}}"""
        } }
        val result = engine.query("n", offline, listOf(source("bad"), source("slow"), source("fast")))
        assertEquals("first", result.name); assertEquals("error", result.evidence[1].status); assertTrue(result.conflict)
    }
    @Test fun `timeouts cancel fetches and preserve offline`() = runBlocking {
        var canceled = false
        val engine = IdentityQueryEngine(Cache()) { _, _ -> try { delay(10_000); "{}" } finally { canceled = true } }
        val result = engine.query("n", offline, listOf(source()), timeoutMs = 250)
        assertEquals("timeout", result.evidence.last().status); assertEquals("无锡市", result.city); assertTrue(canceled)
    }
    @Test fun `parent cancellation is not cached as API error`() = runBlocking {
        val cache = Cache(); val entered = CompletableDeferred<Unit>()
        val engine = IdentityQueryEngine(cache) { _, _ -> entered.complete(Unit); delay(10_000); "{}" }
        val job = launch { engine.query("n", offline, listOf(source())) }; entered.await(); job.cancelAndJoin()
        assertTrue(cache.values.isEmpty())
    }
    @Test fun `at most three network requests execute concurrently`() = runBlocking {
        val active = AtomicInteger(); val maximum = AtomicInteger()
        val engine = IdentityQueryEngine(Cache()) { _, _ ->
            val current = active.incrementAndGet(); maximum.updateAndGet { maxOf(it, current) }
            try { delay(20); """{"code":0}""" } finally { active.decrementAndGet() }
        }
        engine.query("n", offline, (1..6).map { source("$it") }); assertTrue(maximum.get() <= 3); assertEquals(0, active.get())
    }
    @Test fun `errors back off briefly without becoming negative results`() = runBlocking {
        var now = 0L; val count = AtomicInteger()
        val engine = IdentityQueryEngine(Cache(), { now }) { _, _ -> count.incrementAndGet(); error("bad") }
        val result = engine.query("n", offline, listOf(source())); engine.query("n", offline, listOf(source()))
        assertEquals(1, count.get()); assertEquals("unknown", result.risk)
        now = 120_000; engine.query("n", offline, listOf(source())); assertEquals(2, count.get())
    }
    @Test fun `disabled sources never upload numbers`() = runBlocking {
        val engine = IdentityQueryEngine(Cache()) { _, _ -> error("must not fetch") }
        assertEquals(1, engine.query("n", offline, listOf(source().copy(enabled = false))).evidence.size)
    }
    @Test fun `policy separates mark identity no result and error lifetimes`() {
        val p = IdentityCachePolicy()
        assertEquals(168 * 3_600_000L, p.ttlMillis(IdentityEvidence("a", "A", name = "企业")))
        assertEquals(24 * 3_600_000L, p.ttlMillis(IdentityEvidence("a", "A", name = "企业", risk = "high")))
        assertEquals(6 * 3_600_000L, p.ttlMillis(IdentityEvidence("a", "A", status = "empty")))
        assertEquals(120_000L, p.ttlMillis(IdentityEvidence("a", "A", status = "error")))
        assertEquals(3_600_000L, IdentityCachePolicy(enterpriseHours = 1, markHours = 24)
            .ttlMillis(IdentityEvidence("a", "A", name = "企业", risk = "high")))
    }
    @Test fun `new call and hide invalidate old session and cancel work`() {
        val token = CallerIdentitySession.begin(); val job = Job(); CallerIdentitySession.track(token, job)
        CallerIdentitySession.invalidate(); assertFalse(CallerIdentitySession.active(token)); assertTrue(job.isCancelled)
        val newer = CallerIdentitySession.begin(); assertTrue(CallerIdentitySession.active(newer))
        val stale = Job(); CallerIdentitySession.track(token, stale); assertTrue(stale.isCancelled)
    }
}
