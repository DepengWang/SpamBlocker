package spam.blocker.util

import android.provider.Settings
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import spam.blocker.db.Db
import spam.blocker.db.NumberIdentityCache
import spam.blocker.ui.widgets.FloatingWindow

@RunWith(AndroidJUnit4::class)
class CnIdentityDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync { block() }

    @Test fun cacheTableRoundTripExpiryAndExistingSchema() {
        val db = Db.getInstance(ctx).writableDatabase
        val key = "cn-identity-instrumentation-only"
        val number = "+8613800138000"
        val cache = NumberIdentityCache(ctx)
        try {
            val evidence = IdentityEvidence("fixture", "Fixture", name = "测试企业", queriedAt = 10)
            cache.put(number, key, evidence, 100)
            assertEquals(evidence, cache.get(number, key, 99))
            assertNull(cache.get(number, key, 100))
            // Original tables must survive the additive extension migration.
            for (table in listOf(Db.TABLE_CALL, Db.TABLE_SMS, Db.TABLE_NUMBER_RULE, Db.TABLE_API_QUERY)) {
                db.rawQuery("SELECT count(*) FROM $table", null).use { assertTrue(it.moveToFirst()) }
            }
        } finally { db.delete("cn_identity_cache", "source_key = ?", arrayOf(key)) }
    }

    @Test fun bundledDatabaseWorksWithoutOnlineAdapter() {
        val result = CnNumberLookup.lookupOffline(ctx, "+8617798734255")
        assertEquals("江苏省", result.province)
        assertEquals("无锡市", result.city)
        assertFalse(result.carrier.isNullOrBlank())
    }

    @Test fun refreshingContentPreservesTimerAndCannotResurrectWindow() {
        assumeTrue(Settings.canDrawOverlays(ctx))
        val token = CallerIdentitySession.begin()
        try {
            main {
                FloatingWindow.update(ctx, bgColor = 0xEE202124.toInt())
                FloatingWindow.show(ctx, TextView(ctx).apply { text = "CN test" }, durationMs = 600)
            }
            Thread.sleep(200)
            main { assertTrue(FloatingWindow.replaceContent(ctx, TextView(ctx).apply { text = "CN updated" })) }
            Thread.sleep(650)
            main { assertFalse(FloatingWindow.replaceContent(ctx, TextView(ctx).apply { text = "must not reopen" })) }
            assertFalse(CallerIdentitySession.active(token))
        } finally { main { FloatingWindow.hide(ctx) } }
    }

    @Test fun hidingWindowRejectsLateUpdate() {
        assumeTrue(Settings.canDrawOverlays(ctx))
        try {
            main {
                FloatingWindow.show(ctx, TextView(ctx).apply { text = "CN lifecycle test" })
                FloatingWindow.hide(ctx)
                assertFalse(FloatingWindow.replaceContent(ctx, TextView(ctx)))
            }
            Thread.sleep(200)
            main { assertFalse(FloatingWindow.replaceContent(ctx, TextView(ctx))) }
        } finally { main { FloatingWindow.hide(ctx) } }
    }
}
