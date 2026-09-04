package com.myvpn.android.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/** Presentation-only Russian phone input. The UI stores only the ten digits after +7. */
data class PhoneNumberInput(val subscriberDigits: String) {
    val formatted: String get() = PhoneNumberInputFormatter.format(subscriberDigits)
    val canonical: String get() = "+7$subscriberDigits"
    val isComplete: Boolean get() = subscriberDigits.length == 10
    val prefix: String get() = PhoneNumberInputFormatter.prefix
}

object PhoneNumberInputFormatter {
    const val prefix = "+7"

    fun fromUserInput(value: String): PhoneNumberInput = PhoneNumberInput(normalize(value))

    /** Retains the cursor around the same raw subscriber digit after paste or invalid characters. */
    fun normalize(value: TextFieldValue): TextFieldValue {
        val raw = normalize(value.text)
        if (value.text == raw) return value
        val cursor = subscriberDigitsBefore(value.text, value.selection.end).coerceIn(0, raw.length)
        return TextFieldValue(raw, TextRange(cursor))
    }

    fun normalize(value: String): String {
        return subscriberDigitIndexes(value).map { value[it] }.joinToString("").take(10)
    }

    fun format(subscriberDigits: String): String {
        val value = subscriberDigits.take(10)
        return buildString {
            append(value.take(3))
            if (value.length > 3) append('-').append(value.substring(3, minOf(6, value.length)))
            if (value.length > 6) append('-').append(value.substring(6, minOf(8, value.length)))
            if (value.length > 8) append('-').append(value.substring(8, minOf(10, value.length)))
        }
    }

    private fun subscriberDigitsBefore(value: String, offset: Int): Int = subscriberDigitIndexes(value).count { it < offset }.coerceAtMost(10)

    private fun subscriberDigitIndexes(value: String): List<Int> {
        val digitIndexes = value.indices.filter { value[it].isDigit() }
        val digits = digitIndexes.map { value[it] }.joinToString("")
        val skipCountryCode = (value.trim().startsWith("+") && digits.startsWith("7")) ||
                (digits.length >= 11 && (digits.startsWith("7") || digits.startsWith("8")))
        return if (skipCountryCode) digitIndexes.drop(1) else digitIndexes
    }
}

/** Applies separators only to subscriber digits; the Material +7 prefix is outside this mapping. */
object PhoneNumberVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText = TransformedText(AnnotatedString(PhoneNumberInputFormatter.format(text.text)), PhoneNumberOffsetMapping)
}

object PhoneNumberOffsetMapping : OffsetMapping {
    private val originals = intArrayOf(0, 1, 2, 3, 5, 6, 7, 9, 10, 12, 13)
    private val transformed = intArrayOf(0, 1, 2, 3, 3, 4, 5, 6, 6, 7, 8, 8, 9, 10)
    override fun originalToTransformed(offset: Int): Int = originals[offset.coerceIn(0, 10)]
    override fun transformedToOriginal(offset: Int): Int = transformed[offset.coerceIn(0, 13)]
}
