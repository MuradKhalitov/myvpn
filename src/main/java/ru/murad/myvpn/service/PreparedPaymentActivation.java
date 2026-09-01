package ru.murad.myvpn.service;

import ru.murad.myvpn.model.PaymentProviderType;
import ru.murad.myvpn.model.PaymentActivationStatus;
import ru.murad.myvpn.model.PaymentStatus;
import ru.murad.myvpn.model.VpnAccessStatus;
import java.time.Instant;
import java.util.UUID;

public record PreparedPaymentActivation(UUID paymentOrderId, UUID accountId,
        PaymentProviderType provider, PaymentActivationAction action,
        long generation, UUID token, int durationDays, Instant targetExpiresAt,
        UUID existingSubscriptionId, UUID existingVpnAccessId,
        String stableExternalClientId, Long existingSubscriptionVersion,
        Instant existingSubscriptionExpiresAt, PaymentStatus paymentStatus,
        PaymentActivationStatus activationStatus, UUID tariffId,
        String tariffCodeSnapshot, String tariffNameSnapshot,
        String vpnProviderName, Long existingVpnAccessVersion,
        VpnAccessStatus existingVpnAccessStatus, String existingVpnProviderName) {
    public PreparedPaymentActivation(UUID paymentOrderId, UUID accountId,
            PaymentProviderType provider, PaymentActivationAction action,
            long generation, UUID token, int durationDays, Instant targetExpiresAt,
            UUID existingSubscriptionId, UUID existingVpnAccessId,
            String stableExternalClientId, Long existingSubscriptionVersion,
            Instant existingSubscriptionExpiresAt) {
        this(paymentOrderId, accountId, provider, action, generation, token, durationDays,
                targetExpiresAt, existingSubscriptionId, existingVpnAccessId,
                stableExternalClientId, existingSubscriptionVersion,
                existingSubscriptionExpiresAt, PaymentStatus.SUCCEEDED,
                PaymentActivationStatus.PROCESSING, null, null, null, "FAKE",
                null, null, null);
    }

    @Override public String toString() { return "PreparedPaymentActivation[redacted]"; }

    /** @deprecated compatibility accessor for tests; the ownership key is accountId. */
    @Deprecated
    public UUID userId() {
        return accountId;
    }

    public PreparedPaymentActivation withTargetExpiresAt(Instant target) {
        return new PreparedPaymentActivation(paymentOrderId, accountId, provider, action, generation, token,
                durationDays, target, existingSubscriptionId, existingVpnAccessId,
                stableExternalClientId, existingSubscriptionVersion, existingSubscriptionExpiresAt,
                paymentStatus, activationStatus, tariffId, tariffCodeSnapshot, tariffNameSnapshot,
                vpnProviderName, existingVpnAccessVersion, existingVpnAccessStatus,
                existingVpnProviderName);
    }
}
