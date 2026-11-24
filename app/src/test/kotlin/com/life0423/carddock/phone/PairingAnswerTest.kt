package com.life0423.carddock.phone

import org.junit.Assert.*
import org.junit.Test

class PairingAnswerTest {
    @Test fun codeOnlyKeepsLeadingZero() {
        val answer = checkNotNull(PairingAnswer.parse(" 012345 "))
        assertEquals("012345", answer.code)
        assertNull(answer.port)
    }

    @Test fun manualPortRemainsCompatible() {
        for (separator in listOf(" ", "：", ",")) {
            val answer = checkNotNull(PairingAnswer.parse("37123" + separator + "012345"))
            assertEquals(37123, answer.port)
            assertEquals("012345", answer.code)
        }
    }

    @Test fun malformedAnswerDoesNotStartPairing() {
        for (input in listOf("01234", "1234567", "0 012345", "65536 012345",
            "host:37123 012345", "012345 37123")) assertNull(PairingAnswer.parse(input))
    }
}
