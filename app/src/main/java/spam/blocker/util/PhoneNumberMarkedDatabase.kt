package spam.blocker.util

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import spam.blocker.R
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import spam.blocker.util.IdentityEvidence
import spam.blocker.util.IdentityJson
import java.io.File

/** Reads the bundled xdtianyu/PhoneNumber marked-number snapshot (2016-03-11). */
internal object PhoneNumberMarkedDatabase {
    const val SOURCE_NAME = "PhoneNumber 本地标记库（2016）"
    private const val DB_FILE = "phonenumber_marked_20160311.db"
    private val lock = Any()

    private val labels = listOf(
        "骚扰电话",
        "诈骗",
        "推销",
        "广告推销",
        "房产中介",
        "快递送餐",
        "诈骗电话",
        "外卖",
        "中介",
        "快递",
    )

    fun labelForType(type: Int): String? = labels.getOrNull(type - 1)

    internal fun numberCandidates(rawNumber: String, nationalNumber: String?): List<String> {
        val candidates = linkedSetOf<String>()
        fun add(value: String) {
            if (value.length !in 7..12 || !value.all(Char::isDigit)) return
            candidates += value
            if (!value.startsWith('0') && value.length < 12) candidates += "0$value"
        }

        nationalNumber?.let(::add)
        val rawDigits = rawNumber.filter(Char::isDigit)
        add(rawDigits)
        val trimmed = rawNumber.trim()
        when {
            trimmed.startsWith("+86") -> add(rawDigits.drop(2))
            trimmed.startsWith("0086") -> add(rawDigits.drop(4))
        }
        return candidates.toList()
    }

    fun find(context: Context, rawNumber: String, nationalNumber: String?): String? {
        val candidates = numberCandidates(rawNumber, nationalNumber)
        if (candidates.isEmpty()) return null
        val file = installDatabase(context) ?: return null
        return runCatching {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                candidates.forEach { number ->
                    db.rawQuery(
                        "SELECT type FROM phone_number WHERE number = ? LIMIT 1",
                        arrayOf(number),
                    ).use { cursor ->
                        if (cursor.moveToFirst()) return@runCatching labelForType(cursor.getInt(0))
                    }
                }
                null
            }
        }.getOrNull()
    }

    fun cachedApiResult(context: Context, rawNumber: String, nationalNumber: String?): IdentityEvidence? {
        val candidates = numberCandidates(rawNumber, nationalNumber)
        if (candidates.isEmpty()) return null
        val file = installDatabase(context) ?: return null
        return runCatching {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                candidates.forEach { number ->
                    db.rawQuery("SELECT payload FROM tmini_mark_cache WHERE number = ? LIMIT 1",
                        arrayOf(number)).use { cursor ->
                        if (cursor.moveToFirst()) return@runCatching IdentityJson.json.decodeFromString<IdentityEvidence>(cursor.getString(0))
                    }
                }
                null
            }
        }.getOrNull()
    }

    fun saveApiResult(context: Context, rawNumber: String, nationalNumber: String?, evidence: IdentityEvidence) {
        if (evidence.sourceId != "tmini-checkmark" || evidence.status !in listOf("ok", "empty")) return
        val number = numberCandidates(rawNumber, nationalNumber).firstOrNull() ?: return
        val file = installDatabase(context) ?: return
        runCatching {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.insertWithOnConflict("tmini_mark_cache", null, ContentValues().apply {
                    put("number", number)
                    put("payload", IdentityJson.json.encodeToString(evidence))
                    put("expires_at", Long.MAX_VALUE)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    /** Persist Tmini geography only into fields missing from the existing local lookup. */
    fun saveRegionOverlayIfMissing(
        context: Context,
        rawNumber: String,
        nationalNumber: String?,
        local: IdentityEvidence,
        evidence: IdentityEvidence,
    ) {
        if (evidence.sourceId != "tmini-checkmark" || evidence.status !in listOf("ok", "empty")) return
        val number = numberCandidates(rawNumber, nationalNumber).firstOrNull() ?: return
        val province = evidence.province?.takeIf { local.province.isNullOrBlank() }
        val city = evidence.city?.takeIf { local.city.isNullOrBlank() }
        val carrier = evidence.carrier?.takeIf { local.carrier.isNullOrBlank() }
        if (province == null && city == null && carrier == null) return
        val file = installDatabase(context) ?: return
        runCatching {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                val values = ContentValues().apply {
                    put("number", number)
                    province?.let { put("province", it) }
                    city?.let { put("city", it) }
                    carrier?.let { put("carrier", it) }
                    put("updated_at", evidence.queriedAt)
                }
                val inserted = db.insertWithOnConflict("tmini_region_overlay", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                if (inserted == -1L) {
                    values.remove("number")
                    db.update("tmini_region_overlay", values, "number = ?", arrayOf(number))
                }
            }
        }
    }

    fun cachedRegionOverlay(context: Context, rawNumber: String, nationalNumber: String?): IdentityEvidence? {
        val candidates = numberCandidates(rawNumber, nationalNumber)
        if (candidates.isEmpty()) return null
        val file = installDatabase(context) ?: return null
        return runCatching {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                candidates.forEach { number ->
                    db.rawQuery(
                        "SELECT province, city, carrier, updated_at FROM tmini_region_overlay WHERE number = ? LIMIT 1",
                        arrayOf(number),
                    ).use { cursor ->
                        if (cursor.moveToFirst()) {
                            return@runCatching IdentityEvidence(
                                sourceId = "tmini-checkmark", source = "Tmini 本地归属补充",
                                status = "ok",
                                province = cursor.getString(0), city = cursor.getString(1), carrier = cursor.getString(2),
                                queriedAt = cursor.getLong(3),
                            )
                        }
                    }
                }
                null
            }
        }.getOrNull()
    }

    fun savedResultCount(context: Context): Int? {
        val file = installDatabase(context) ?: return null
        return runCatching {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT COUNT(*) FROM tmini_mark_cache", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
            }
        }.getOrNull()
    }

    fun clearApiCache(context: Context): Boolean {
        val file = installDatabase(context) ?: return false
        return runCatching {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.beginTransaction()
                try {
                    db.delete("tmini_mark_cache", null, null)
                    db.delete("tmini_region_overlay", null, null)
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            true
        }.getOrDefault(false)
    }

    private fun installDatabase(context: Context): File? = synchronized(lock) {
        val destination = context.getDatabasePath(DB_FILE)
        if (destination.isFile && destination.length() > 0L) {
            ensureApiCacheTable(destination)
            return@synchronized destination
        }
        val parent = destination.parentFile ?: return@synchronized null
        if (!parent.exists() && !parent.mkdirs()) return@synchronized null

        val temporary = File(parent, "$DB_FILE.tmp")
        val copied = runCatching {
            context.resources.openRawResource(R.raw.marked_2016).use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            if (temporary.renameTo(destination)) true
            else {
                // Only replace this app-private, versioned cache file if a previous copy is corrupt.
                destination.delete()
                temporary.renameTo(destination)
            }
        }.getOrDefault(false)
        if (!copied) temporary.delete()
        destination.takeIf { copied && it.isFile && it.length() > 0L }?.also(::ensureApiCacheTable)
    }

    private fun ensureApiCacheTable(file: File) {
        runCatching {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("CREATE TABLE IF NOT EXISTS tmini_mark_cache (number TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL, expires_at INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_tmini_mark_cache_expiry ON tmini_mark_cache(expires_at)")
                db.execSQL("CREATE TABLE IF NOT EXISTS tmini_region_overlay (number TEXT PRIMARY KEY NOT NULL, province TEXT, city TEXT, carrier TEXT, updated_at INTEGER NOT NULL)")
                // Older app versions used expiring cache entries. Promote them to durable records
                // so an upgrade does not trigger another paid request for a known number.
                db.execSQL("UPDATE tmini_mark_cache SET expires_at = ? WHERE expires_at < ?",
                    arrayOf(Long.MAX_VALUE, Long.MAX_VALUE))
            }
        }
    }
}
