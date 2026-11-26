package com.nexa.mobile.operations

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayLanguageTest {
    @Test
    fun tagsResolveToSupportedChoicesAndUnknownValuesDefaultToEnglish() {
        assertEquals(DisplayLanguage.English, DisplayLanguage.fromTag(null))
        assertEquals(DisplayLanguage.English, DisplayLanguage.fromTag("unknown"))
        assertEquals(DisplayLanguage.System, DisplayLanguage.fromTag("system"))
        assertEquals(DisplayLanguage.English, DisplayLanguage.fromTag("en-US"))
        assertEquals(DisplayLanguage.Spanish, DisplayLanguage.fromTag("es-419"))
    }
}
