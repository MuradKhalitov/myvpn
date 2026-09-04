package com.myvpn.android.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumberInputTest {
    @Test fun emptyInputHasFixedPrefixAndSuffixPlaceholder() {
        val input = PhoneNumberInputFormatter.fromUserInput("")
        assertEquals("+7", input.prefix)
        assertEquals("999-123-45-67", input.placeholder)
        assertEquals("", input.formatted)
        assertFalse(input.isComplete)
    }

    @Test fun oneThroughTenDigitsAreFormattedAsSubscriberNumber() {
        val expected = listOf("9", "96", "963", "963-3", "963-37", "963-375", "963-375-1", "963-375-10", "963-375-10-0", "963-375-10-02")
        "9633751002".indices.forEach { index ->
            assertEquals(expected[index], PhoneNumberInputFormatter.fromUserInput("9633751002".take(index + 1)).formatted)
        }
    }

    @Test fun completeNumberHasCanonicalE164Value() {
        val input = PhoneNumberInputFormatter.fromUserInput("9633751002")
        assertEquals("963-375-10-02", input.formatted)
        assertEquals("+79633751002", input.canonical)
        assertTrue(input.isComplete)
    }

    @Test fun russianCountryPrefixesAreNormalizedToSubscriberDigits() {
        assertEquals("+79633751002", PhoneNumberInputFormatter.fromUserInput("89633751002").canonical)
        assertEquals("+79633751002", PhoneNumberInputFormatter.fromUserInput("+79633751002").canonical)
        assertEquals("9633751002", PhoneNumberInputFormatter.fromUserInput("+7 (963) 375-10-02").subscriberDigits)
    }

    @Test fun lettersAndExtraDigitsAreIgnoredOrTrimmed() {
        val input = PhoneNumberInputFormatter.fromUserInput("abc963375100299")
        assertEquals("9633751002", input.subscriberDigits)
        assertEquals("963-375-10-02", input.formatted)
    }

    @Test fun continueIsEnabledOnlyForTenSubscriberDigits() {
        assertFalse(PhoneNumberInputFormatter.fromUserInput("963375100").isComplete)
        assertTrue(PhoneNumberInputFormatter.fromUserInput("9633751002").isComplete)
    }

    @Test fun offsetMappingMatchesEveryRawCursorPosition() {
        val expected = intArrayOf(0, 1, 2, 3, 5, 6, 7, 9, 10, 12, 13)
        expected.forEachIndexed { offset, transformed ->
            assertEquals(transformed, PhoneNumberOffsetMapping.originalToTransformed(offset))
        }
    }

    @Test fun reverseOffsetMappingIsMonotonicAndBounded() {
        var previous = -1
        for (offset in 0..13) {
            val original = PhoneNumberOffsetMapping.transformedToOriginal(offset)
            assertTrue(original in 0..10)
            assertTrue(original >= previous)
            previous = original
        }
        assertEquals(3, PhoneNumberOffsetMapping.transformedToOriginal(4))
        assertEquals(6, PhoneNumberOffsetMapping.transformedToOriginal(8))
        assertEquals(8, PhoneNumberOffsetMapping.transformedToOriginal(11))
    }

    @Test fun typingBackspaceAndMiddleEditKeepRawSelection() {
        var value = TextFieldValue("", TextRange(0))
        "9633751002".forEachIndexed { index, digit ->
            value = PhoneNumberInputFormatter.normalize(TextFieldValue(value.text + digit, TextRange(index + 1)))
            assertEquals(index + 1, value.selection.end)
        }
        value = PhoneNumberInputFormatter.normalize(TextFieldValue("963375100", TextRange(9)))
        assertEquals("963375100", value.text)
        assertEquals(9, value.selection.end)
        value = PhoneNumberInputFormatter.normalize(TextFieldValue("963371002", TextRange(6)))
        assertEquals("963371002", value.text)
        assertEquals(6, value.selection.end)
    }

    @Test fun pastedPrefixNormalizesToRawDigitsAndSelection() {
        val value = PhoneNumberInputFormatter.normalize(TextFieldValue("+7 (963) 375-10-02", TextRange(18)))
        assertEquals("9633751002", value.text)
        assertEquals(10, value.selection.end)
    }
}
