package spam.blocker.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PhoneNumberMarkedDatabaseTest {
    @Test fun `legacy mark types map to their original labels`() {
        assertEquals("骚扰电话", PhoneNumberMarkedDatabase.labelForType(1))
        assertEquals("诈骗", PhoneNumberMarkedDatabase.labelForType(2))
        assertEquals("快递送餐", PhoneNumberMarkedDatabase.labelForType(6))
        assertEquals("快递", PhoneNumberMarkedDatabase.labelForType(10))
        assertNull(PhoneNumberMarkedDatabase.labelForType(0))
        assertNull(PhoneNumberMarkedDatabase.labelForType(11))
    }

    @Test fun `lookup candidates handle domestic and international prefixes`() {
        assertEquals(
            listOf("13800138000", "013800138000"),
            PhoneNumberMarkedDatabase.numberCandidates("+86 13800138000", "13800138000"),
        )
        assertEquals(
            listOf("1050861064", "01050861064"),
            PhoneNumberMarkedDatabase.numberCandidates("010-5086-1064", "1050861064"),
        )
    }
}
