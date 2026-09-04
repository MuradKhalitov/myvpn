package com.myvpn.android.ui

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object PresentationFormatter {
    private val locale = Locale("ru", "RU")
    private val dateFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", locale)

    fun premiumUntil(iso: String?): String? = iso?.let { value ->
        runCatching { "До ${Instant.parse(value).atZone(ZoneId.systemDefault()).format(dateFormatter)}" }.getOrNull()
    }

    fun trialRemaining(iso: String?, now: Instant = Instant.now()): String? = iso?.let { value ->
        runCatching { trialRemaining(Duration.between(now, Instant.parse(value))) }.getOrNull()
    }

    fun trialRemaining(remaining: Duration): String {
        if (remaining.isZero || remaining.isNegative) return "Пробный период завершён"
        val days = remaining.toDays()
        if (days >= 1) return "Осталось $days ${plural(days, "день", "дня", "дней")}" 
        val hours = remaining.toHours()
        if (hours >= 1) return "Осталось $hours ${plural(hours, "час", "часа", "часов")}" 
        val minutes = maxOf(1, remaining.toMinutes())
        return "Осталось $minutes ${plural(minutes, "минута", "минуты", "минут")}" 
    }

    fun verificationCountdown(iso: String?, now: Instant = Instant.now()): String = iso?.let { value ->
        runCatching { verificationCountdown(Duration.between(now, Instant.parse(value))) }.getOrDefault("Время подтверждения истекло")
    } ?: "Время подтверждения истекло"

    fun verificationCountdown(remaining: Duration): String {
        if (remaining.isZero || remaining.isNegative) return "Время подтверждения истекло"
        val totalSeconds = remaining.seconds
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "Осталось $minutes мин $seconds сек" else "Осталось $seconds сек"
    }

    fun formatPhoneInput(value: String): String {
        val digits = value.filter(Char::isDigit).removePrefix("8").let { if (it.startsWith("7")) it else "7$it" }.take(11)
        if (digits.isEmpty()) return ""
        return buildString {
            append('+').append(digits.take(1))
            if (digits.length > 1) append(' ').append(digits.substring(1, minOf(4, digits.length)))
            if (digits.length > 4) append(' ').append(digits.substring(4, minOf(7, digits.length)))
            if (digits.length > 7) append('-').append(digits.substring(7, minOf(9, digits.length)))
            if (digits.length > 9) append('-').append(digits.substring(9, minOf(11, digits.length)))
        }
    }

    private fun plural(value: Long, one: String, few: String, many: String): String {
        val lastTwo = value % 100
        if (lastTwo in 11..14) return many
        return when (value % 10) { 1L -> one; 2L, 3L, 4L -> few; else -> many }
    }
}
