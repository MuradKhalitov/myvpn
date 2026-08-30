package ru.murad.myvpn.application.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
@RequiredArgsConstructor
public class EmailOtpRequestService {

    private final EmailNormalizer emailNormalizer;
    private final EmailOtpTransactionService transactionService;
    private final EmailSender emailSender;

    public void request(String email) {
        String normalizedEmail;
        try {
            normalizedEmail = emailNormalizer.normalize(email);
        } catch (InvalidEmailException exception) {
            return;
        }

        OtpPreparation preparation = transactionService.prepare(normalizedEmail);
        if (!preparation.shouldSend()) {
            return;
        }
        try {
            emailSender.sendOtp(
                    preparation.recipient(), preparation.code(), preparation.ttl());
        } catch (RuntimeException exception) {
            transactionService.invalidateAfterDeliveryFailure(preparation.challengeId());
        }
    }
}
