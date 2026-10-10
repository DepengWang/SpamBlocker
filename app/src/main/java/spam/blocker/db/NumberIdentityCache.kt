package spam.blocker.db

import android.content.ContentValues
import android.content.Context
import kotlinx.serialization.encodeToString
import spam.blocker.util.IdentityCache
import spam.blocker.util.IdentityEvidence
import spam.blocker.util.IdentityJson

/** Additive CN extension table in the existing database; no upstream version collision. */
class NumberIdentityCache(ctx: Context) : IdentityCache {
    private val helper = Db.getInstance(ctx)
    override fun get(number: String, key: String, now: Long): IdentityEvidence? {
        helper.readableDatabase.query("cn_identity_cache", arrayOf("payload"),
            "number = ? AND source_key = ? AND expires_at > ?", arrayOf(number, key, now.toString()),
            null, null, null).use {
            if (!it.moveToFirst()) return null
            return runCatching { IdentityJson.json.decodeFromString<IdentityEvidence>(it.getString(0)) }.getOrNull()
        }
    }
    override fun put(number: String, key: String, evidence: IdentityEvidence, expiresAt: Long) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict("cn_identity_cache", null, ContentValues().apply {
                put("number", number); put("source_key", key)
                put("payload", IdentityJson.json.encodeToString(evidence)); put("expires_at", expiresAt)
            }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
            db.execSQL("DELETE FROM cn_identity_cache WHERE rowid IN (SELECT rowid FROM cn_identity_cache ORDER BY expires_at DESC LIMIT -1 OFFSET 2000)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun clear() { helper.writableDatabase.delete("cn_identity_cache", null, null) }
}
