package com.myvpn.android.ui

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PresentationFormatterTest {
    @Test fun premiumIsoIsRenderedAsRussianDate() {
        assertEquals("До 9 октября 2026", PresentationFormatter.premiumUntil("2026-10-09T20:26:46.589978Z"))
    }

    @Test fun trialMoreThanDayUsesDays() {
        assertEquals("Осталось 4 дня", PresentationFormatter.trialRemaining(Duration.ofDays(4).plusHours(12)))
    }

    @Test fun trialLessThanDayUsesHoursNotDays() {
        assertEquals("Осталось 12 часов", PresentationFormatter.trialRemaining(Duration.ofHours(12)))
    }

    @Test fun trialLessThanHourUsesMinutesWithoutZeroDays() {
        val text = PresentationFormatter.trialRemaining(Duration.ofMinutes(24))
        assertEquals("Осталось 24 минуты", text)
        assertFalse(text.contains("0 д"))
    }

    @Test fun verificationCountdownUsesMinutesAndSeconds() {
        assertEquals("Осталось 4 мин 35 сек", PresentationFormatter.verificationCountdown(Duration.ofMinutes(4).plusSeconds(35)))
        assertEquals("Осталось 45 сек", PresentationFormatter.verificationCountdown(Duration.ofSeconds(45)))
    }

    @Test fun expiredVerificationCountdownHasDedicatedText() {
        assertEquals("Время подтверждения истекло", PresentationFormatter.verificationCountdown(Duration.ZERO))
    }

    @Test fun presentationStringsNeverExposeRawIsoTimestamp() {
        val raw = "2026-10-09T20:26:46.589978Z"
        assertFalse(PresentationFormatter.premiumUntil(raw)!!.contains(raw))
        assertFalse(PresentationFormatter.trialRemaining(raw, Instant.parse("2026-10-05T20:26:46Z"))!!.contains(raw))
    }
}
