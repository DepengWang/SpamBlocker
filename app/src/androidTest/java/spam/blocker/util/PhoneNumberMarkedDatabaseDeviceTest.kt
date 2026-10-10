package spam.blocker.util

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneNumberMarkedDatabaseDeviceTest {
    @Test fun bundledMarkedDatabaseReturnsCategory() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(
            "骚扰电话",
            PhoneNumberMarkedDatabase.find(context, "01050861064", "1050861064"),
        )
    }

    @Test fun internationalNumbersDoNotReceiveMainlandMarkTags() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val (_, evidence) = IdentityLookup.offline(context, "+1 202-555-0100")
        assertNull(evidence.category)
    }

    @Test fun successfulApiResultPersistsInTheWritableMarkedDatabaseCopy() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val number = "13900001234"
        val evidence = IdentityEvidence("tmini-checkmark", "Tmini", status = "empty", province = "江苏省", city = "无锡市",
            carrier = "中国移动", queriedAt = 123,
            category = null)
        val local = IdentityEvidence("offline", "libphonenumber", province = "已有省", carrier = "已有运营商")
        try {
            PhoneNumberMarkedDatabase.saveApiResult(context, number, number, evidence)
            PhoneNumberMarkedDatabase.saveRegionOverlayIfMissing(context, number, number, local, evidence)
            val restored = PhoneNumberMarkedDatabase.cachedApiResult(context, number, number)
            assertNotNull(restored)
            assertEquals("empty", restored?.status)
            assertEquals("江苏省", restored?.province)
            val region = PhoneNumberMarkedDatabase.cachedRegionOverlay(context, number, number)
            assertEquals("无锡市", region?.city)
            assertNull(region?.province)
            assertNull(region?.carrier)
            SQLiteDatabase.openDatabase(context.getDatabasePath("phonenumber_marked_20160311.db").absolutePath,
                null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT expires_at FROM tmini_mark_cache WHERE number = ?", arrayOf(number)).use { cursor ->
                    assertEquals(true, cursor.moveToFirst())
                    assertEquals(Long.MAX_VALUE, cursor.getLong(0))
                }
            }
        } finally {
            context.getDatabasePath("phonenumber_marked_20160311.db").takeIf { it.exists() }?.let { file ->
                SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                    db.delete("tmini_mark_cache", "number = ?", arrayOf(number))
                    db.delete("tmini_region_overlay", "number = ?", arrayOf(number))
                }
            }
        }
    }
}
