package ru.murad.myvpn.application.auth;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailOtpRequestServiceTest {

    private final EmailOtpTransactionService transactions = mock(EmailOtpTransactionService.class);
    private final EmailSender sender = mock(EmailSender.class);
    private final EmailOtpRequestService service = new EmailOtpRequestService(
            new EmailNormalizer(), transactions, sender);

    @Test
    void sendsPreparedOtpAndDoesNotExposeInvalidEmail() {
        UUID id = UUID.randomUUID();
        when(transactions.prepare("user@example.com"))
                .thenReturn(new OtpPreparation(
                        id, "user@example.com", "012345", Duration.ofMinutes(5), true));

        service.request(" User@Example.com ");
        service.request("invalid");

        verify(sender).sendOtp("user@example.com", "012345", Duration.ofMinutes(5));
        verify(transactions, never()).prepare("invalid");
    }

    @Test
    void invalidatesChallengeWhenSmtpFails() {
        UUID id = UUID.randomUUID();
        when(transactions.prepare("user@example.com"))
                .thenReturn(new OtpPreparation(
                        id, "user@example.com", "123456", Duration.ofMinutes(5), true));
        org.mockito.Mockito.doThrow(new IllegalStateException("smtp unavailable"))
                .when(sender).sendOtp("user@example.com", "123456", Duration.ofMinutes(5));

        service.request("user@example.com");

        verify(transactions).invalidateAfterDeliveryFailure(id);
    }

    @Test
    void cooldownDoesNotSendAnotherCode() {
        when(transactions.prepare("user@example.com")).thenReturn(OtpPreparation.cooldown());

        service.request("user@example.com");

        verify(sender, never()).sendOtp(any(), any(), any());
    }

    @Test
    void generatedOtpHasSixDigitsAndPreservesLeadingZeros() {
        java.security.SecureRandom random = mock(java.security.SecureRandom.class);
        when(random.nextInt(1_000_000)).thenReturn(123);

        org.assertj.core.api.Assertions.assertThat(new OtpCodeGenerator(random).generate())
                .isEqualTo("000123");
    }
}
