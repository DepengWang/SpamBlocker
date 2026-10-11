package spam.blocker.config

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CallerIdConfigTest {
    @Test
    fun oldBackupKeepsOriginalAnswerBehavior() {
        val restored = Json.decodeFromString<CallerID>("""{"enabled":true,"template":"{reason}"}""")
        assertFalse(restored.keepAfterAnswer)
        assertFalse(restored.showOutgoing)
        assertFalse(restored.transparentBackground)
        assertTrue(restored.showSources)
        assertEquals(0, restored.theme)
        assertTrue(restored.showContact)
        assertTrue(restored.showForContacts)
        assertEquals(0, restored.durationSec)
        assertEquals(14, restored.fontSizeSp)
        assertEquals("{reason}", restored.template)
    }

    @Test
    fun backupRoundTripRetainsAllNewOptions() {
        val original = CallerID().apply {
            keepAfterAnswer = true
            showContact = false
            showForContacts = false
            durationSec = 15
            fontSizeSp = 20
            showOutgoing = true
            showSources = false
            theme = 2
            transparentBackground = true
        }
        val restored = Json.decodeFromString<CallerID>(Json.encodeToString(original))
        assertTrue(restored.keepAfterAnswer)
        assertFalse(restored.showContact)
        assertFalse(restored.showForContacts)
        assertEquals(15, restored.durationSec)
        assertEquals(20, restored.fontSizeSp)
        assertTrue(restored.showOutgoing)
        assertFalse(restored.showSources)
        assertEquals(2, restored.theme)
        assertTrue(restored.transparentBackground)
    }

    @Test
    fun oldApiBackupHasNoEnabledIdentitySources() {
        val restored = Json.decodeFromString<ApiQuery>("""{"priority":-1}""")
        assertEquals("[]", restored.identitySources)
        assertEquals("{}", restored.identityCachePolicy)
        assertEquals(3000, restored.identityTimeoutMs)
    }

    @Test
    fun apiBackupRetainsGenericAdapterAndCacheOptions() {
        val original = ApiQuery().apply {
            identitySources = "[{\"id\":\"test\",\"name\":\"Test\",\"enabled\":false}]"
            identityCachePolicy = "{\"markHours\":12}"
            identityTimeoutMs = 2000
        }
        val restored = Json.decodeFromString<ApiQuery>(Json.encodeToString(original))
        assertEquals(original.identitySources, restored.identitySources)
        assertEquals(original.identityCachePolicy, restored.identityCachePolicy)
        assertEquals(2000, restored.identityTimeoutMs)
    }
}
