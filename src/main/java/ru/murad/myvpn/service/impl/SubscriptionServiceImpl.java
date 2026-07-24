package ru.murad.myvpn.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import ru.murad.myvpn.client.ProvisionedVpnAccess;
import ru.murad.myvpn.client.VpnExtensionRequest;
import ru.murad.myvpn.client.VpnProvider;
import ru.murad.myvpn.client.VpnProvisionRequest;
import ru.murad.myvpn.dto.ActivateSubscriptionRequest;
import ru.murad.myvpn.dto.RevokeSubscriptionRequest;
import ru.murad.myvpn.dto.SubscriptionDto;
import ru.murad.myvpn.exception.VpnAccessNotFoundException;
import ru.murad.myvpn.exception.ThreeXUiUncertainException;
import ru.murad.myvpn.mapper.SubscriptionMapper;
import ru.murad.myvpn.model.SubscriptionStatus;
import ru.murad.myvpn.model.VpnAccess;
import ru.murad.myvpn.repository.SubscriptionRepository;
import ru.murad.myvpn.repository.VpnAccessRepository;
import ru.murad.myvpn.service.ActivationPreparation;
import ru.murad.myvpn.service.AdminAuthorizationService;
import ru.murad.myvpn.service.SubscriptionService;
import ru.murad.myvpn.service.SubscriptionTransactionService;
import ru.murad.myvpn.service.VpnAccessCandidate;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Service
@Validated
@RequiredArgsConstructor
public class SubscriptionServiceImpl implements SubscriptionService {

    private final AdminAuthorizationService adminAuthorizationService;
    private final SubscriptionRepository subscriptionRepository;
    private final VpnAccessRepository accessRepository;
    private final VpnProvider vpnProvider;
    private final SubscriptionMapper subscriptionMapper;
    private final SubscriptionTransactionService transactionService;
    private final Clock clock;

    @Override
    public SubscriptionDto activate(ActivateSubscriptionRequest request) {
        adminAuthorizationService.checkAccess(request.administratorTelegramId());
        Instant now = clock.instant();
        ActivationPreparation preparation =
                transactionService.prepareActivation(request, now);

        return switch (preparation.type()) {
            case EXTEND -> extend(preparation, request, now);
            case REPLACE_EXPIRED -> replaceExpired(preparation, request, now);
            case PROVISION -> provision(preparation, now);
        };
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SubscriptionDto> findCurrent(long userTelegramId) {
        Instant now = clock.instant();
        return subscriptionRepository
                .findFirstByUserTelegramIdAndStatusAndExpiresAtAfterOrderByExpiresAtDesc(
                        userTelegramId, SubscriptionStatus.ACTIVE, now)
                .map(subscription -> {
                    VpnAccess access = accessRepository.findBySubscriptionId(subscription.getId())
                            .orElseThrow(() ->
                                    new VpnAccessNotFoundException(subscription.getId()));
                    return subscriptionMapper.toDto(subscription, access);
                });
    }

    @Override
    public void revoke(RevokeSubscriptionRequest request) {
        adminAuthorizationService.checkAccess(request.administratorTelegramId());
        VpnAccessCandidate candidate =
                transactionService.findActiveAccess(request.userTelegramId());
        vpnProvider.revoke(candidate.externalAccessId());
        transactionService.completeRevocation(candidate.subscriptionId(), clock.instant());
    }

    private SubscriptionDto provision(
            ActivationPreparation preparation,
            Instant now
    ) {
        ProvisionedVpnAccess provisioned;
        try {
            provisioned = vpnProvider.provision(
                    new VpnProvisionRequest(
                            preparation.subscriptionId(),
                            preparation.userTelegramId(),
                            preparation.expiresAt()));
        } catch (ThreeXUiUncertainException exception) {
            transactionService.markProvisionReconciliationRequired(
                    preparation.subscriptionId(), clock.instant());
            throw exception;
        } catch (RuntimeException exception) {
            transactionService.markProvisionFailed(
                    preparation.subscriptionId(), clock.instant());
            throw exception;
        }
        return transactionService.completeProvision(
                preparation.subscriptionId(), provisioned, now);
    }

    private SubscriptionDto extend(
            ActivationPreparation preparation,
            ActivateSubscriptionRequest request,
            Instant now
    ) {
        vpnProvider.extend(new VpnExtensionRequest(
                preparation.existingExternalAccessId(), preparation.expiresAt()));
        return transactionService.completeExtension(
                preparation.subscriptionId(),
                request.tariffCode(),
                request.administratorTelegramId(),
                preparation.expiresAt(),
                now);
    }

    private SubscriptionDto replaceExpired(
            ActivationPreparation preparation,
            ActivateSubscriptionRequest request,
            Instant now
    ) {
        vpnProvider.revoke(preparation.existingExternalAccessId());
        transactionService.completeExpiration(preparation.subscriptionId(), now);
        ActivationPreparation replacement =
                transactionService.prepareActivation(request, clock.instant());
        return provision(replacement, clock.instant());
    }
}
