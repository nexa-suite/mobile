package com.nexa.mobile.operations.core.device.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ProductCodeCandidateTest {
    @Test
    fun trimsCandidateAndRedactsItsStringRepresentation() {
        val candidate = ProductCodeCandidate.from("  07501234567890  ")

        assertEquals("07501234567890", candidate?.value)
        assertFalse(candidate.toString().contains("07501234567890"))
    }

    @Test
    fun rejectsBlankAndOversizedCandidates() {
        assertNull(ProductCodeCandidate.from(" \n "))
        assertNull(ProductCodeCandidate.from("x".repeat(161)))
    }
}
