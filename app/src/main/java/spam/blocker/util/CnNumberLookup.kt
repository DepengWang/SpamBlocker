package spam.blocker.util

import android.content.Context
import spam.blocker.R
import com.google.i18n.phonenumbers.PhoneNumberToCarrierMapper
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.geocoding.PhoneNumberOfflineGeocoder
import java.util.Locale

/**
 * Offline number normalization and identity lookup for the China mainland use case.
 *
 * This class deliberately uses libphonenumber's bundled metadata instead of an online
 * service. An unknown number is returned with no guessed location or carrier.
 */
object CnNumberLookup {
    const val DATABASE_VERSION = "libphonenumber-9.0.39"
    const val DATABASE_SOURCE = "libphonenumber-offline"
    private const val PHONE_DATABASE_VERSION = "phone.dat-2502"
    private const val DEFAULT_REGION = "CN"

    data class NormalizedNumber(
        val e164: String,
        val nationalNumber: String,
        val countryCode: Int,
        val regionCode: String?,
    )

    enum class Category {
        MOBILE,
        LANDLINE,
        ENTERPRISE_SERVICE_400,
        ENTERPRISE_SERVICE_800,
        SPECIAL_SERVICE,
        INTERNATIONAL,
        UNKNOWN,
    }

    data class Result(
        val rawNumber: String,
        val normalizedNumber: String? = null,
        val nationalNumber: String? = null,
        val countryCode: Int? = null,
        val regionCode: String? = null,
        val province: String? = null,
        val city: String? = null,
        val location: String? = null,
        val carrier: String? = null,
        val category: Category = Category.UNKNOWN,
        val source: String? = null,
        val databaseVersion: String? = null,
    )

    private data class ChinaPhoneRecord(
        val province: String?,
        val city: String?,
        val carrier: String?,
    )

    private data class Parsed(
        val normalized: NormalizedNumber,
        val phoneNumber: com.google.i18n.phonenumbers.Phonenumber.PhoneNumber,
    )

    /**
     * Normalizes +86 and 0086 without treating an unprefixed 86 as China.
     * Other international prefixes are passed to libphonenumber unchanged.
     */
    fun normalizeNumber(rawNumber: String, defaultRegion: String = DEFAULT_REGION): NormalizedNumber? {
        val parsed = parse(rawNumber, defaultRegion) ?: return null
        return parsed.normalized
    }

    /**
     * Looks up a number using only libphonenumber's local geocoder and carrier metadata.
     */
    fun lookupOffline(
        rawNumber: String,
        defaultRegion: String = DEFAULT_REGION,
        locale: Locale = Locale.getDefault(),
    ): Result {
        val raw = rawNumber.trim()
        val parsed = parse(raw, defaultRegion)
        if (parsed == null) {
            return Result(rawNumber = raw)
        }

        val normalized = parsed.normalized
        val isChina = normalized.countryCode == 86 || normalized.regionCode == "CN"
        val numberLocale = if (isChina) Locale.SIMPLIFIED_CHINESE else locale
        val category = categoryOf(parsed.phoneNumber, normalized, isChina)

        val location = try {
            PhoneNumberOfflineGeocoder.getInstance()
                .getDescriptionForNumber(parsed.phoneNumber, numberLocale)
                .trim()
                .takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
        val carrier = try {
            PhoneNumberToCarrierMapper.getInstance()
                .getNameForNumber(parsed.phoneNumber, numberLocale)
                .trim()
                .takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
        val (province, city) = if (isChina) splitChineseLocation(location) else null to null

        return Result(
            rawNumber = raw,
            normalizedNumber = normalized.e164,
            nationalNumber = normalized.nationalNumber,
            countryCode = normalized.countryCode,
            regionCode = normalized.regionCode,
            province = province,
            city = city,
            location = location,
            carrier = carrier,
            category = category,
            source = DATABASE_SOURCE,
            databaseVersion = DATABASE_VERSION,
        )
    }

    /**
     * Adds the more precise seven-digit China number-prefix database to the
     * libphonenumber result. The database is bundled in the APK and never
     * sends the number to a network service.
     */
    fun lookupOffline(
        context: Context,
        rawNumber: String,
        defaultRegion: String = DEFAULT_REGION,
        locale: Locale = Locale.getDefault(),
    ): Result {
        val base = lookupOffline(rawNumber, defaultRegion, locale)
        if (base.countryCode != 86 || base.nationalNumber == null) return base

        val record = ChinaPhoneDatabase.find(context, base.nationalNumber)
        val local = if (record == null) base else base.copy(
            province = record.province ?: base.province,
            city = record.city ?: base.city,
            location = listOfNotNull(record.province ?: base.province, record.city ?: base.city)
                .joinToString("").takeIf { it.isNotEmpty() } ?: base.location,
            carrier = record.carrier ?: base.carrier,
            source = "$DATABASE_SOURCE+$PHONE_DATABASE_VERSION",
            databaseVersion = "$DATABASE_VERSION+$PHONE_DATABASE_VERSION",
        )
        // Tmini results live in the writable marked.db cache and act as a per-number
        // overlay; they fill gaps only and never overwrite bundled phone.dat metadata.
        val tmini = PhoneNumberMarkedDatabase.cachedRegionOverlay(context, base.normalizedNumber!!, base.nationalNumber)
            ?: PhoneNumberMarkedDatabase.cachedApiResult(context, base.normalizedNumber, base.nationalNumber)
        return applyTminiFallback(local, tmini)
    }

    internal fun applyTminiFallback(base: Result, tmini: IdentityEvidence?): Result {
        if (base.countryCode != 86 || tmini == null) return base
        val province = base.province ?: tmini.province
        val city = base.city ?: tmini.city
        val carrier = base.carrier ?: tmini.carrier
        val filledGap = (base.province == null && tmini.province != null) ||
            (base.city == null && tmini.city != null) || (base.carrier == null && tmini.carrier != null)
        if (!filledGap) return base
        val location = base.location ?: listOfNotNull(province, city).joinToString("").takeIf { it.isNotEmpty() }
        return base.copy(province = province, city = city, carrier = carrier, location = location,
            source = listOfNotNull(base.source, "Tmini-cache").distinct().joinToString("+"),
            databaseVersion = listOfNotNull(base.databaseVersion, "Tmini-cache").distinct().joinToString("+"))
    }

    private fun parse(rawNumber: String, defaultRegion: String): Parsed? {
        val sanitized = sanitize(rawNumber) ?: return null
        return try {
            val phoneUtil = PhoneNumberUtil.getInstance()
            // Telecom often supplies a bare national number. Prefer the CN parser for
            // unprefixed Chinese shapes even when the device/SIM is configured for a
            // different country (for example an imported OPPO IN build).
            val parseRegion = if (isLikelyChinaNationalNumber(sanitized)) {
                DEFAULT_REGION
            } else {
                defaultRegion.ifBlank { DEFAULT_REGION }
            }
            val phoneNumber = phoneUtil.parse(sanitized, parseRegion)
            if (!phoneUtil.isPossibleNumber(phoneNumber)) {
                return null
            }
            val countryCode = phoneNumber.countryCode
            val normalized = NormalizedNumber(
                e164 = phoneUtil.format(phoneNumber, PhoneNumberUtil.PhoneNumberFormat.E164),
                nationalNumber = phoneNumber.nationalNumber.toString(),
                countryCode = countryCode,
                regionCode = phoneUtil.getRegionCodeForNumber(phoneNumber),
            )
            Parsed(normalized, phoneNumber)
        } catch (_: Exception) {
            null
        }
    }

    private fun isLikelyChinaNationalNumber(number: String): Boolean {
        if (number.startsWith("+")) return false
        return number.matches(Regex("1[3-9]\\d{9}")) ||
            number.matches(Regex("(?:400|800)\\d{7}")) ||
            number.matches(Regex("9[56]\\d{3,6}"))
    }

    private fun sanitize(rawNumber: String): String? {
        val compact = rawNumber.trim().replace(Regex("[^0-9+]"), "")
        if (compact.isEmpty() || compact.count { it == '+' } > 1 ||
            (compact.contains('+') && !compact.startsWith('+'))
        ) {
            return null
        }

        // ITU international dialing prefix. This also handles 0086 explicitly.
        return if (compact.startsWith("00")) "+${compact.drop(2)}" else compact
    }

    private fun categoryOf(
        phoneNumber: com.google.i18n.phonenumbers.Phonenumber.PhoneNumber,
        normalized: NormalizedNumber,
        isChina: Boolean,
    ): Category {
        if (!isChina) return Category.INTERNATIONAL

        val national = normalized.nationalNumber
        return when {
            national.matches(Regex("400\\d{7}")) -> Category.ENTERPRISE_SERVICE_400
            national.matches(Regex("800\\d{7}")) -> Category.ENTERPRISE_SERVICE_800
            national.matches(Regex("9[56]\\d{3,6}")) -> Category.SPECIAL_SERVICE
            PhoneNumberUtil.getInstance().getNumberType(phoneNumber) == PhoneNumberUtil.PhoneNumberType.MOBILE ->
                Category.MOBILE
            PhoneNumberUtil.getInstance().getNumberType(phoneNumber) == PhoneNumberUtil.PhoneNumberType.FIXED_LINE ->
                Category.LANDLINE
            else -> Category.UNKNOWN
        }
    }

    private fun splitChineseLocation(location: String?): Pair<String?, String?> {
        val value = location?.replace("中国", "")?.trim()?.takeIf { it.isNotEmpty() } ?: return null to null
        val directMunicipalities = setOf("北京", "上海", "天津", "重庆", "北京市", "上海市", "天津市", "重庆市")
        if (value in directMunicipalities) {
            return value.removeSuffix("市") + "市" to null
        }

        val province = when {
            value.contains("特别行政区") -> value.substringBefore("特别行政区") + "特别行政区"
            value.contains("自治区") -> value.substringBefore("自治区") + "自治区"
            value.contains("省") -> value.substringBefore("省") + "省"
            else -> null
        }
        val city = province?.let {
            value.removePrefix(it).trim().takeIf { remainder -> remainder.isNotEmpty() }
        }
        return province to city
    }

    private object ChinaPhoneDatabase {
        private const val RECORD_SIZE = 9
        @Volatile
        private var bytes: ByteArray? = null

        fun find(context: Context, nationalNumber: String): ChinaPhoneRecord? {
            if (nationalNumber.length < 7 || !nationalNumber.take(7).all(Char::isDigit)) return null
            val data = load(context) ?: return null
            val firstIndex = littleEndianInt(data, 4)
            if (firstIndex < 8 || firstIndex >= data.size) return null

            val target = nationalNumber.take(7).toIntOrNull() ?: return null
            val recordCount = (data.size - firstIndex) / RECORD_SIZE
            var left = 0
            var right = recordCount - 1
            while (left <= right) {
                val middle = (left + right) ushr 1
                val indexOffset = firstIndex + middle * RECORD_SIZE
                val current = littleEndianInt(data, indexOffset)
                when {
                    current < target -> left = middle + 1
                    current > target -> right = middle - 1
                    else -> {
                        val textOffset = littleEndianInt(data, indexOffset + 4)
                        return parseRecord(data, textOffset, data[indexOffset + 8].toInt() and 0xff)
                    }
                }
            }
            return null
        }

        private fun load(context: Context): ByteArray? {
            bytes?.let { return it }
            return synchronized(this) {
                bytes ?: runCatching {
                    context.applicationContext.resources
                        .openRawResource(R.raw.phone)
                        .use { it.readBytes() }
                }.getOrNull()?.also { bytes = it }
            }
        }

        private fun parseRecord(data: ByteArray, offset: Int, type: Int): ChinaPhoneRecord? {
            if (offset !in data.indices) return null
            var end = offset
            while (end < data.size && data[end].toInt() != 0) end++
            if (end == data.size) return null

            val fields = data.copyOfRange(offset, end).toString(Charsets.UTF_8).split('|')
            if (fields.size < 4) return null
            return ChinaPhoneRecord(
                province = fields[0].trim().takeIf { it.isNotEmpty() }?.withProvinceSuffix(),
                city = fields[1].trim().takeIf { it.isNotEmpty() }?.withCitySuffix(),
                carrier = carrierName(type),
            )
        }

        private fun carrierName(type: Int?): String? = when (type) {
            1 -> "中国移动"
            2 -> "中国联通"
            3 -> "中国电信"
            4 -> "中国电信虚拟运营商"
            5 -> "中国联通虚拟运营商"
            6 -> "中国移动虚拟运营商"
            7 -> "中国广电"
            8 -> "中国广电虚拟运营商"
            else -> null
        }

        private fun littleEndianInt(data: ByteArray, offset: Int): Int {
            if (offset < 0 || offset + 4 > data.size) return -1
            return (data[offset].toInt() and 0xff) or
                ((data[offset + 1].toInt() and 0xff) shl 8) or
                ((data[offset + 2].toInt() and 0xff) shl 16) or
                ((data[offset + 3].toInt() and 0xff) shl 24)
        }

        private fun String.withProvinceSuffix(): String = when {
            endsWith("省") || endsWith("自治区") || endsWith("特别行政区") -> this
            this == "北京" || this == "上海" || this == "天津" || this == "重庆" -> this + "市"
            else -> this + "省"
        }

        private fun String.withCitySuffix(): String = when {
            endsWith("市") || endsWith("州") || endsWith("地区") || endsWith("盟") -> this
            else -> this + "市"
        }
    }
}
