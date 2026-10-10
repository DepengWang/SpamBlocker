package spam.blocker.db

import android.content.ContentValues
import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import spam.blocker.util.IdentityEvidence
import spam.blocker.util.IdentityJson

@Serializable
data class CnNumberLookupRecord(
    val id: Long = 0,
    val number: String,
    val lookedUpAt: Long,
    val evidenceJson: String,
) {
    fun evidence(): List<IdentityEvidence> = runCatching {
        IdentityJson.json.decodeFromString<List<IdentityEvidence>>(evidenceJson)
    }.getOrDefault(emptyList())
}

object CnNumberHistory {
    private fun db(ctx: Context) = Db.getInstance(ctx).writableDatabase

    fun save(ctx: Context, number: String, evidence: List<IdentityEvidence>): Long {
        val database = db(ctx)
        val now = System.currentTimeMillis()
        val id = database.insert("cn_number_lookup_history", null, ContentValues().apply {
            put("number", number)
            put("looked_up_at", now)
            put("evidence", IdentityJson.json.encodeToString(evidence))
        })
        database.execSQL("DELETE FROM cn_number_lookup_history WHERE id NOT IN (SELECT id FROM cn_number_lookup_history ORDER BY looked_up_at DESC LIMIT 500)")
        return id
    }

    fun list(ctx: Context, limit: Int = 30): List<CnNumberLookupRecord> =
        Db.getInstance(ctx).readableDatabase.query("cn_number_lookup_history", null, null, null,
            null, null, "looked_up_at DESC", limit.coerceIn(1, 100).toString()).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(CnNumberLookupRecord(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    number = cursor.getString(cursor.getColumnIndexOrThrow("number")),
                    lookedUpAt = cursor.getLong(cursor.getColumnIndexOrThrow("looked_up_at")),
                    evidenceJson = cursor.getString(cursor.getColumnIndexOrThrow("evidence")),
                ))
            }
        }

    fun delete(ctx: Context, id: Long) {
        db(ctx).delete("cn_number_lookup_history", "id = ?", arrayOf(id.toString()))
    }

    fun setMark(ctx: Context, number: String, label: String?) {
        val database = db(ctx)
        if (label.isNullOrBlank()) database.delete("cn_number_marks", "number = ?", arrayOf(number))
        else database.insertWithOnConflict("cn_number_marks", null, ContentValues().apply {
            put("number", number); put("label", label.trim().take(80)); put("updated_at", System.currentTimeMillis())
        }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun mark(ctx: Context, number: String): String? =
        Db.getInstance(ctx).readableDatabase.query("cn_number_marks", arrayOf("label"),
            "number = ?", arrayOf(number), null, null, null).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
}
