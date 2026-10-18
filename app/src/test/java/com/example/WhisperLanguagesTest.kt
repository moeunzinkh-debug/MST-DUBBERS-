package com.example

import com.example.data.api.WhisperLanguages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperLanguagesTest {

    @Test
    fun `normalize returns null for auto and unknown codes`() {
        assertNull(WhisperLanguages.normalize("auto"))
        assertNull(WhisperLanguages.normalize(""))
        assertNull(WhisperLanguages.normalize(null))
        assertNull(WhisperLanguages.normalize("xx")) // not a selectable Whisper language
    }

    @Test
    fun `normalize accepts ISO codes with casing and region suffixes`() {
        assertEquals("km", WhisperLanguages.normalize("km"))
        assertEquals("km", WhisperLanguages.normalize(" KM "))
        assertEquals("km", WhisperLanguages.normalize("km-KH"))
        assertEquals("zh", WhisperLanguages.normalize("zh-CN"))
    }

    @Test
    fun `stored settings are sanitized back to auto when invalid`() {
        assertEquals("auto", WhisperLanguages.sanitizeSetting("junk"))
        assertEquals("auto", WhisperLanguages.sanitizeSetting(null))
        assertEquals("km", WhisperLanguages.sanitizeSetting("km"))
        assertEquals("auto", WhisperLanguages.sanitizeSetting("AUTO"))
    }

    @Test
    fun `track languages match both 639-1 and 639-2 spellings`() {
        assertTrue(WhisperLanguages.trackLanguageMatches("km", "km"))
        assertTrue(WhisperLanguages.trackLanguageMatches("khm", "km"))
        assertTrue(WhisperLanguages.trackLanguageMatches("km-KH", "km"))
        assertTrue(WhisperLanguages.trackLanguageMatches("zh-CN", "zh"))
        assertTrue(WhisperLanguages.trackLanguageMatches("zho", "zh"))
        assertTrue(WhisperLanguages.trackLanguageMatches("eng", "en"))
        assertFalse(WhisperLanguages.trackLanguageMatches(null, "km"))
        assertFalse(WhisperLanguages.trackLanguageMatches("", "km"))
        assertFalse(WhisperLanguages.trackLanguageMatches("km", "zh"))
        assertFalse(WhisperLanguages.trackLanguageMatches("tha", "km"))
    }

    @Test
    fun `labels resolve for settings values`() {
        assertTrue(WhisperLanguages.labelFor("auto").contains("Auto"))
        assertTrue(WhisperLanguages.labelFor("km").contains("Khmer"))
        assertTrue(WhisperLanguages.labelFor("junk").contains("Auto")) // falls back to auto
    }
}
