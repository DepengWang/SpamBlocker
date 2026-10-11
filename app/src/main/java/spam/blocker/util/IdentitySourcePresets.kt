package spam.blocker.util

/** Presets are appended explicitly; loading one never replaces the user's other sources. */
object IdentitySourcePresets {
    fun tmini(enabled: Boolean = false) = IdentitySource(
        id = "tmini-checkmark", name = "Tmini", enabled = enabled,
        url = "https://api.tmini.net/apis/checkmark",
        parameters = mapOf("key" to "{shared_pref(tmini_api_key)}", "phone" to "{domestic}"),
        paths = mapOf("province" to "region", "company" to "certName", "category" to "results.*.tags.*.name"),
        successPath = "code", successValue = "200")

    fun juhe(enabled: Boolean = false) = IdentitySource(
        id = "callerinfo-juhe", name = "Juhe", apiId = 1, enabled = enabled,
        url = "https://apis.juhe.cn/mobile/get",
        parameters = mapOf("phone" to "{domestic}", "key" to "{shared_pref(juhe_api_key)}"),
        paths = mapOf("province" to "result.province", "city" to "result.city", "carrier" to "result.company"),
        successPath = "error_code", successValue = "0")

    fun custom(id: String) = IdentitySource(id, "Custom API", url = "https://example.com/lookup",
        parameters = mapOf("number" to "{number}"),
        paths = mapOf("name" to "data.name", "province" to "data.province", "city" to "data.city",
            "carrier" to "data.carrier", "category" to "data.category", "risk" to "data.riskLevel"),
        successPath = "code", successValue = "0")
}
