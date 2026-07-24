package ru.murad.myvpn.service;

import ru.murad.myvpn.client.ProvisionedVpnAccess;
import ru.murad.myvpn.dto.ActivateSubscriptionRequest;
import ru.murad.myvpn.dto.SubscriptionDto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SubscriptionTransactionService {

    ActivationPreparation prepareActivation(
            ActivateSubscriptionRequest request,
            Instant now
    );

    SubscriptionDto completeProvision(
            UUID subscriptionId,
            ProvisionedVpnAccess provisioned,
            Instant now
    );

    boolean completeProvision(
            UUID subscriptionId,
            UUID claimToken,
            ProvisionedVpnAccess provisioned,
            Instant now
    );

    SubscriptionDto completeExtension(
            UUID subscriptionId,
            String tariffCode,
            long administratorTelegramId,
            Instant expectedExpiry,
            Instant now
    );

    void markProvisionFailed(UUID subscriptionId, Instant now);

    boolean markProvisionFailed(UUID subscriptionId, UUID claimToken, Instant now);

    void markProvisionReconciliationRequired(UUID subscriptionId, Instant now);

    VpnAccessCandidate findActiveAccess(long userTelegramId);

    void completeRevocation(UUID subscriptionId, Instant now);

    void completeExpiration(UUID subscriptionId, Instant now);

    List<VpnAccessCandidate> findExpiredAccesses(Instant now);

    List<PendingProvisionCandidate> claimPendingProvisioning(
            Instant staleBefore,
            Instant now,
            String owner
    );

    boolean releaseProvisioningClaim(
            UUID subscriptionId,
            UUID claimToken,
            Instant now
    );

    boolean markManualReviewRequired(
            UUID subscriptionId,
            UUID claimToken,
            Instant now
    );
}
