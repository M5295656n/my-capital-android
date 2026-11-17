package ru.mycapital.aibenchmark

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Sha256VerifierTest {
    private val validHash = "d2387ca2dbfee2ffabce7120d3770dadca0b293052bc2f0e138fdc940d9bc7b5"

    @Test
    fun matchingHashIsAccepted() {
        assertTrue(Sha256Verifier.matches(validHash, validHash))
    }

    @Test
    fun differentHashIsRejected() {
        assertFalse(Sha256Verifier.matches(validHash, "0".repeat(64)))
    }

    @Test
    fun missingActualHashIsRejected() {
        assertFalse(Sha256Verifier.matches(validHash, null))
    }

    @Test
    fun malformedExpectedHashIsRejected() {
        assertFalse(Sha256Verifier.matches("not-a-hash", validHash))
        assertFalse(Sha256Verifier.isValid("a".repeat(63)))
        assertFalse(Sha256Verifier.isValid("g".repeat(64)))
    }
}
