package ru.murad.myvpn.service;

import ru.murad.myvpn.client.ProvisionedVpnAccess;
import java.time.Instant;
import java.util.List;

public interface PaymentActivationTransactionService {
    List<PreparedPaymentActivation> claimActivations(Instant now, int limit);
    int markExhaustedActivations(Instant now, int limit);
    PaymentActivationOutcome complete(PreparedPaymentActivation prepared,
                                      ProvisionedVpnAccess result, Instant now);
    PaymentActivationOutcome retry(PreparedPaymentActivation prepared, String code, Instant now);
    PaymentActivationOutcome manualReview(PreparedPaymentActivation prepared, String code, Instant now);

    enum PaymentActivationOutcome { SUCCEEDED, RETRY_SCHEDULED, MANUAL_REVIEW_REQUIRED,
        ALREADY_ACTIVATED, STALE, SKIPPED }
}
