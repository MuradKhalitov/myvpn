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
import ru.murad.myvpn.dto.SubscriptionDto;
import ru.murad.myvpn.exception.TelegramUserNotFoundException;
import ru.murad.myvpn.exception.VpnAccessNotFoundException;
import ru.murad.myvpn.exception.VpnTariffNotFoundException;
import ru.murad.myvpn.mapper.SubscriptionMapper;
import ru.murad.myvpn.model.Subscription;
import ru.murad.myvpn.model.SubscriptionStatus;
import ru.murad.myvpn.model.TelegramUser;
import ru.murad.myvpn.model.VpnAccess;
import ru.murad.myvpn.model.VpnAccessStatus;
import ru.murad.myvpn.model.VpnTariff;
import ru.murad.myvpn.repository.SubscriptionRepository;
import ru.murad.myvpn.repository.TelegramUserRepository;
import ru.murad.myvpn.repository.VpnAccessRepository;
import ru.murad.myvpn.repository.VpnTariffRepository;
import ru.murad.myvpn.service.AdminAuthorizationService;
import ru.murad.myvpn.service.SubscriptionService;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Service
@Validated
@RequiredArgsConstructor
public class SubscriptionServiceImpl implements SubscriptionService {

    private final AdminAuthorizationService adminAuthorizationService;
    private final TelegramUserRepository userRepository;
    private final VpnTariffRepository tariffRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final VpnAccessRepository accessRepository;
    private final VpnProvider vpnProvider;
    private final SubscriptionMapper subscriptionMapper;
    private final Clock clock;

    @Override
    @Transactional
    public SubscriptionDto activate(ActivateSubscriptionRequest request) {
        adminAuthorizationService.checkAccess(request.administratorTelegramId());
        Instant now = clock.instant();
        TelegramUser user = userRepository.findByTelegramId(request.userTelegramId())
                .orElseThrow(() -> new TelegramUserNotFoundException(request.userTelegramId()));
        VpnTariff tariff = tariffRepository.findByCodeAndActiveTrue(request.tariffCode())
                .orElseThrow(() -> new VpnTariffNotFoundException(request.tariffCode()));

        Optional<Subscription> activeSubscription =
                subscriptionRepository.findFirstByUserIdAndStatusOrderByExpiresAtDesc(
                        user.getId(), SubscriptionStatus.ACTIVE);

        if (activeSubscription.isPresent()
                && activeSubscription.get().getExpiresAt().isAfter(now)) {
            return extend(activeSubscription.get(), tariff, request.administratorTelegramId(), now);
        }

        activeSubscription.ifPresent(subscription -> expire(subscription, now));
        return provision(user, tariff, request.administratorTelegramId(), now);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SubscriptionDto> findCurrent(long userTelegramId) {
        Instant now = clock.instant();
        return subscriptionRepository
                .findFirstByUserTelegramIdAndStatusAndExpiresAtAfterOrderByExpiresAtDesc(
                        userTelegramId, SubscriptionStatus.ACTIVE, now)
                .map(subscription -> {
                    VpnAccess access = findAccess(subscription);
                    return subscriptionMapper.toDto(subscription, access);
                });
    }

    private SubscriptionDto extend(
            Subscription subscription,
            VpnTariff tariff,
            long administratorTelegramId,
            Instant now
    ) {
        VpnAccess access = findAccess(subscription);
        subscription.extend(tariff, administratorTelegramId, now);
        vpnProvider.extend(new VpnExtensionRequest(
                access.getExternalAccessId(), subscription.getExpiresAt()));
        subscriptionRepository.save(subscription);
        return subscriptionMapper.toDto(subscription, access);
    }

    private void expire(Subscription subscription, Instant now) {
        VpnAccess access = findAccess(subscription);
        vpnProvider.revoke(access.getExternalAccessId());
        access.revoke(now);
        subscription.markExpired(now);
        accessRepository.save(access);
        subscriptionRepository.saveAndFlush(subscription);
    }

    private SubscriptionDto provision(
            TelegramUser user,
            VpnTariff tariff,
            long administratorTelegramId,
            Instant now
    ) {
        Subscription subscription = Subscription.builder()
                .id(UUID.randomUUID())
                .user(user)
                .tariff(tariff)
                .status(SubscriptionStatus.ACTIVE)
                .startsAt(now)
                .expiresAt(now.plus(tariff.getDurationDays(), ChronoUnit.DAYS))
                .activatedByTelegramId(administratorTelegramId)
                .activatedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        subscriptionRepository.save(subscription);

        ProvisionedVpnAccess provisioned = vpnProvider.provision(new VpnProvisionRequest(
                subscription.getId(), user.getTelegramId(), subscription.getExpiresAt()));
        VpnAccess access = VpnAccess.builder()
                .id(UUID.randomUUID())
                .subscription(subscription)
                .providerName(provisioned.providerName())
                .externalAccessId(provisioned.externalAccessId())
                .configurationData(provisioned.configurationData())
                .status(VpnAccessStatus.ACTIVE)
                .issuedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        accessRepository.save(access);
        return subscriptionMapper.toDto(subscription, access);
    }

    private VpnAccess findAccess(Subscription subscription) {
        return accessRepository.findBySubscriptionId(subscription.getId())
                .orElseThrow(() -> new VpnAccessNotFoundException(subscription.getId()));
    }
}
