package spam.blocker.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CnNumberLookupTest {
    @Test
    fun normalizesChinaInternationalPrefixes() {
        val plus86 = CnNumberLookup.normalizeNumber("+86 138-0013-8000")
        val diallingPrefix = CnNumberLookup.normalizeNumber("0086 138 0013 8000")

        assertEquals("+8613800138000", plus86?.e164)
        assertEquals(plus86, diallingPrefix)
        assertEquals(86, plus86?.countryCode)
    }

    @Test
    fun doesNotTreatUnprefixedForeignCodeAsChina() {
        val number = CnNumberLookup.normalizeNumber("+1 202-555-0100")

        assertEquals("+12025550100", number?.e164)
        assertEquals(1, number?.countryCode)
        assertTrue(CnNumberLookup.lookupOffline("+1 202-555-0100").category == CnNumberLookup.Category.INTERNATIONAL)
    }

    @Test
    fun classifiesChineseServiceNumbersWithoutInventingLocation() {
        val result400 = CnNumberLookup.lookupOffline("400-123-4567")
        val result800 = CnNumberLookup.lookupOffline("800-123-4567")

        assertEquals(CnNumberLookup.Category.ENTERPRISE_SERVICE_400, result400.category)
        assertEquals(CnNumberLookup.Category.ENTERPRISE_SERVICE_800, result800.category)
        assertNull(result400.province)
        assertNull(result800.city)
    }

    @Test
    fun unknownInputRetainsOnlyOriginalNumber() {
        val result = CnNumberLookup.lookupOffline("not-a-phone-number")

        assertEquals("not-a-phone-number", result.rawNumber)
        assertNull(result.normalizedNumber)
        assertNull(result.location)
        assertNull(result.carrier)
        assertEquals(CnNumberLookup.Category.UNKNOWN, result.category)
    }

    @Test
    fun bareChinaMobileUsesChinaEvenWhenDeviceRegionIsForeign() {
        val result = CnNumberLookup.lookupOffline("13800138000", defaultRegion = "IN")

        assertEquals(86, result.countryCode)
        assertEquals(CnNumberLookup.Category.MOBILE, result.category)
        assertEquals("北京市", result.location)
        assertEquals("中国移动", result.carrier)
    }
}
