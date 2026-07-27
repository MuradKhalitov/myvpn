package ru.murad.myvpn.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.Metrics;
import org.springframework.stereotype.Component;
import ru.murad.myvpn.client.threexui.ThreeXUiClientRequest;
import ru.murad.myvpn.client.threexui.ThreeXUiInboundResponse;
import ru.murad.myvpn.client.threexui.ThreeXUiInboundSettings;
import ru.murad.myvpn.client.threexui.ThreeXUiVlessClient;
import ru.murad.myvpn.config.ThreeXUiProperties;
import ru.murad.myvpn.exception.ThreeXUiException;
import ru.murad.myvpn.exception.ThreeXUiNotFoundException;
import ru.murad.myvpn.exception.ThreeXUiRetryableException;
import ru.murad.myvpn.exception.ThreeXUiUncertainException;
import ru.murad.myvpn.exception.ThreeXUiLastClientException;
import ru.murad.myvpn.exception.VpnProviderFailureCode;

import java.util.List;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "vpn.provider.type", havingValue = "3x-ui")
@Slf4j
public class ThreeXUiVpnProvider implements VpnProvider {

    private static final String PROVIDER_NAME = "3X_UI";

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public java.time.Instant resolveProvisionTarget(
            VpnProvisionRequest request,
            int durationDays,
            java.time.Instant now
    ) {
        if (request == null || request.stableExternalAccessId() == null
                || request.stableExternalAccessId().isBlank() || durationDays <= 0 || now == null) {
            throw new ThreeXUiException(VpnProviderFailureCode.INVALID_PROVIDER_RESPONSE,
                    "Invalid 3x-ui provision target request");
        }
        ThreeXUiRequestBudget budget =
                new ThreeXUiRequestBudget(properties.maxRequestsPerOperation());
        ThreeXUiInboundResponse inbound = inboundClient.getInbound(budget);
        validateConfiguredInbound(inbound);
        Optional<ThreeXUiVlessClient> existing =
                findProvisioningClient(inbound, request.stableExternalAccessId());
        java.time.Instant base = existing
                .map(ThreeXUiVlessClient::expiryTime)
                .filter(expiry -> expiry > now.toEpochMilli())
                .map(java.time.Instant::ofEpochMilli)
                .orElse(now);
        try {
            return java.time.Instant.ofEpochMilli(base
                    .plus(java.time.Duration.ofDays(durationDays)).toEpochMilli());
        } catch (java.time.DateTimeException | ArithmeticException exception) {
            throw new ThreeXUiException(VpnProviderFailureCode.INVALID_PROVIDER_RESPONSE,
                    "3x-ui provision target cannot be calculated");
        }
    }
    private static final String EMAIL_PREFIX = "myvpn-";

    private final ThreeXUiInboundClient inboundClient;
    private final VpnConfigurationFactory configurationFactory;
    private final ThreeXUiConfigurationMapper configurationMapper;
    private final ThreeXUiProperties properties;

    public ThreeXUiVpnProvider(
            ThreeXUiInboundClient inboundClient,
            VpnConfigurationFactory configurationFactory,
            ThreeXUiConfigurationMapper configurationMapper,
            ThreeXUiProperties properties
    ) {
        this.inboundClient = inboundClient;
        this.configurationFactory = configurationFactory;
        this.configurationMapper = configurationMapper;
        this.properties = properties;
    }

    @Override
    public ProvisionedVpnAccess provision(VpnProvisionRequest request) {
        ThreeXUiRequestBudget budget =
                new ThreeXUiRequestBudget(properties.maxRequestsPerOperation());
        String clientUuid = request.stableExternalAccessId() == null
                ? request.subscriptionId().toString() : request.stableExternalAccessId();
        long expectedExpiry = request.expiresAt().toEpochMilli();
        ThreeXUiVlessClient client = ThreeXUiVlessClient.create(
                clientUuid, EMAIL_PREFIX + clientUuid, expectedExpiry);
        boolean reconciliationStarted = false;
        for (int attempt = 1; attempt <= properties.maxMutationAttempts(); attempt++) {
            boolean mutationAttempted = false;
            boolean reconciliationAttempted = false;
            ThreeXUiInboundResponse before = null;
            try {
                ThreeXUiInboundResponse inbound = inboundClient.getInbound(budget);
                validateConfiguredInbound(inbound);
                Optional<ThreeXUiVlessClient> existing = findProvisioningClient(inbound, clientUuid);
                if (existing.isPresent()) {
                    if (matchesProvisionedState(existing.get(), expectedExpiry)) {
                        log.info("3x-ui provision operation=reuse expiryChanged=false");
                        return result(inbound, existing.get());
                    }
                    mutationAttempted = true;
                    reconciliationAttempted = true;
                    reconciliationStarted = true;
                    before = inbound;
                    budget.reserveReconciliation();
                    inboundClient.updateClient(
                            clientUuid,
                            inboundClient.prepareProvisionReconciliationRequest(
                                    inbound, clientUuid, expectedExpiry),
                            budget);
                    ThreeXUiInboundResponse confirmed =
                            inboundClient.getInboundForReconciliation(budget);
                    validateConfiguredInbound(confirmed);
                    Optional<ThreeXUiVlessClient> confirmedClient =
                            findProvisioningClient(confirmed, clientUuid);
                    if (confirmedClient.isPresent()
                            && matchesProvisionedState(confirmedClient.get(), expectedExpiry)
                            && inboundClient.otherClientsUnchanged(before, confirmed, clientUuid)) {
                        log.info("3x-ui provision operation=reconcile expiryChanged=true");
                        return result(confirmed, confirmedClient.get());
                    }
                    continue;
                }
                mutationAttempted = true;
                budget.reserveReconciliation();
                inboundClient.addClient(request(client), budget);
                ThreeXUiInboundResponse confirmed =
                        inboundClient.getInboundForReconciliation(budget);
                validateConfiguredInbound(confirmed);
                Optional<ThreeXUiVlessClient> confirmedClient =
                        findProvisioningClient(confirmed, clientUuid);
                if (confirmedClient.isPresent()
                        && matchesProvisionedState(confirmedClient.get(), expectedExpiry)) {
                    log.info("3x-ui provision operation=create expiryChanged=true");
                    return result(confirmed, confirmedClient.get());
                }
            } catch (ThreeXUiRetryableException exception) {
                if (mutationAttempted) {
                    try {
                        ThreeXUiInboundResponse recovered =
                                inboundClient.getInboundForReconciliation(budget);
                        validateConfiguredInbound(recovered);
                        Optional<ThreeXUiVlessClient> recoveredClient =
                                findProvisioningClient(recovered, clientUuid);
                        boolean otherClientsPreserved = !reconciliationAttempted
                                || inboundClient.otherClientsUnchanged(before, recovered, clientUuid);
                        if (recoveredClient.isPresent()
                                && matchesProvisionedState(recoveredClient.get(), expectedExpiry)
                                && otherClientsPreserved) {
                            log.info("3x-ui provision operation={} expiryChanged=true",
                                    reconciliationAttempted ? "reconcile" : "create");
                            return result(recovered, recoveredClient.get());
                        }
                    } catch (ThreeXUiRetryableException ignored) {
                        // The result remains uncertain and consumes the current attempt.
                    }
                }
                if (attempt == properties.maxMutationAttempts()) {
                    throw new ThreeXUiUncertainException();
                }
                inboundClient.pause(attempt);
                continue;
            }
            if (attempt < properties.maxMutationAttempts()) {
                inboundClient.pause(attempt);
            }
        }
        log.warn("3x-ui provision operation=reconcile expiryChanged=true stateConfirmed=false");
        if (reconciliationStarted) {
            throw new ThreeXUiUncertainException();
        }
        throw new ThreeXUiException("3x-ui client creation was not confirmed");
    }

    @Override
    public ProvisionedVpnAccess extend(VpnExtensionRequest request) {
        ThreeXUiRequestBudget budget =
                new ThreeXUiRequestBudget(properties.maxRequestsPerOperation());
        for (int attempt = 1; attempt <= properties.maxMutationAttempts(); attempt++) {
            try {
                ThreeXUiInboundResponse inbound = inboundClient.getInbound(budget);
                ThreeXUiVlessClient existing =
                        findClient(inbound, request.externalAccessId())
                                .orElseThrow(() ->
                                        new ThreeXUiNotFoundException("extend client"));
                long expectedExpiry = request.expiresAt().toEpochMilli();
                if (existing.expiryTime() != null
                        && existing.expiryTime() == expectedExpiry) {
                    return extensionResult(existing);
                }
                budget.reserveReconciliation();
                inboundClient.updateClient(
                        request.externalAccessId(),
                        inboundClient.prepareExpiryUpdateRequest(
                                inbound, request.externalAccessId(), expectedExpiry),
                        budget);
                if (isExpiryAppliedAndOthersPreserved(
                        inbound, request.externalAccessId(),
                        expectedExpiry, budget)) {
                    return extensionResult(findClient(inboundClient.getInboundForReconciliation(budget), request.externalAccessId()).orElseThrow());
                }
            } catch (ThreeXUiRetryableException exception) {
                try {
                    if (isExpiryApplied(request.externalAccessId(),
                            request.expiresAt().toEpochMilli(), budget, true)) {
                        return extensionResult(findClient(inboundClient.getInboundForReconciliation(budget), request.externalAccessId()).orElseThrow());
                    }
                } catch (ThreeXUiRetryableException ignored) {
                    // The update remains uncertain within the current attempt.
                }
                if (attempt == properties.maxMutationAttempts()) {
                    throw new ThreeXUiUncertainException();
                }
                inboundClient.pause(attempt);
                continue;
            }
            if (attempt < properties.maxMutationAttempts()) {
                inboundClient.pause(attempt);
            }
        }
        throw new ThreeXUiException("3x-ui client extension was not confirmed");
    }

    private ProvisionedVpnAccess extensionResult(ThreeXUiVlessClient client) {
        Metrics.counter("vpn_provider_operation_total", "provider", "3x_ui", "operation", "extend", "result", "success")
                .increment();
        return new ProvisionedVpnAccess(PROVIDER_NAME, client.id(), null,
                java.time.Instant.ofEpochMilli(client.expiryTime()));
    }

    @Override
    public void revoke(String externalAccessId) {
        ThreeXUiRequestBudget budget =
                new ThreeXUiRequestBudget(properties.maxRequestsPerOperation());
        for (int attempt = 1; attempt <= properties.maxMutationAttempts(); attempt++) {
            try {
                ThreeXUiInboundResponse inbound = inboundClient.getInbound(budget);
                ThreeXUiInboundSettings settings = inboundClient.parseSettings(inbound);
                if (settings.clients().stream()
                        .noneMatch(client -> externalAccessId.equals(client.id()))) {
                    return;
                }
                if (settings.clients().size() == 1) {
                    throw new ThreeXUiLastClientException();
                }
                budget.reserveReconciliation();
                inboundClient.deleteClient(externalAccessId, budget);
                if (findClient(inboundClient.getInboundForReconciliation(budget),
                        externalAccessId).isEmpty()) {
                    return;
                }
            } catch (ThreeXUiNotFoundException | ThreeXUiRetryableException exception) {
                try {
                    if (findClient(inboundClient.getInboundForReconciliation(budget),
                            externalAccessId).isEmpty()) {
                        return;
                    }
                } catch (ThreeXUiRetryableException ignored) {
                    // Continue within the same operation retry budget.
                }
                if (exception instanceof ThreeXUiNotFoundException
                        || attempt == properties.maxMutationAttempts()) {
                    if (exception instanceof ThreeXUiNotFoundException) {
                        throw exception;
                    }
                    throw new ThreeXUiUncertainException();
                }
                inboundClient.pause(attempt);
                continue;
            }
            if (attempt < properties.maxMutationAttempts()) {
                inboundClient.pause(attempt);
            }
        }
        throw new ThreeXUiException("3x-ui client revocation was not confirmed");
    }

    private ThreeXUiClientRequest request(ThreeXUiVlessClient client) {
        String settings = inboundClient.serializeSettings(
                new ThreeXUiInboundSettings(List.of(client)));
        return new ThreeXUiClientRequest(properties.inboundId(), settings);
    }

    private boolean isExpiryApplied(
            String clientUuid,
            long expiryTime,
            ThreeXUiRequestBudget budget,
            boolean reconciliation
    ) {
        ThreeXUiInboundResponse inbound = reconciliation
                ? inboundClient.getInboundForReconciliation(budget)
                : inboundClient.getInbound(budget);
        return findClient(inbound, clientUuid)
                .map(client -> client.expiryTime() != null
                        && client.expiryTime() == expiryTime)
                .orElse(false);
    }

    private boolean isExpiryAppliedAndOthersPreserved(
            ThreeXUiInboundResponse before,
            String clientUuid,
            long expiryTime,
            ThreeXUiRequestBudget budget
    ) {
        ThreeXUiInboundResponse after =
                inboundClient.getInboundForReconciliation(budget);
        boolean expiryApplied = findClient(after, clientUuid)
                .map(client -> client.expiryTime() != null
                        && client.expiryTime() == expiryTime)
                .orElse(false);
        return expiryApplied
                && inboundClient.otherClientsUnchanged(before, after, clientUuid);
    }

    private Optional<ThreeXUiVlessClient> findClient(
            ThreeXUiInboundResponse inbound,
            String clientUuid
    ) {
        return inboundClient.parseSettings(inbound).clients().stream()
                .filter(client -> clientUuid.equals(client.id()))
                .findFirst();
    }

    private Optional<ThreeXUiVlessClient> findProvisioningClient(
            ThreeXUiInboundResponse inbound,
            String clientUuid
    ) {
        List<ThreeXUiVlessClient> matches = inboundClient.parseSettings(inbound).clients().stream()
                .filter(client -> clientUuid.equals(client.id()))
                .toList();
        if (matches.size() > 1) {
            throw new ThreeXUiException(VpnProviderFailureCode.INVALID_PROVIDER_RESPONSE,
                    "3x-ui returned ambiguous client identity");
        }
        return matches.stream().findFirst();
    }

    private boolean matchesProvisionedState(ThreeXUiVlessClient client, long expectedExpiry) {
        return client.expiryTime() != null
                && client.expiryTime() == expectedExpiry
                && Boolean.TRUE.equals(client.enable());
    }

    private void validateConfiguredInbound(ThreeXUiInboundResponse inbound) {
        if (inbound.id() != properties.inboundId()
                || !"vless".equalsIgnoreCase(inbound.protocol())) {
            throw new ThreeXUiException(VpnProviderFailureCode.INVALID_PROVIDER_RESPONSE,
                    "3x-ui returned an unexpected configured inbound");
        }
    }

    private ProvisionedVpnAccess result(
            ThreeXUiInboundResponse inbound,
            ThreeXUiVlessClient client
    ) {
        try {
            String configuration = configurationFactory.create(
                    configurationMapper.map(inbound, client.id()));
            if (configuration == null || configuration.isBlank()) {
                throw new ThreeXUiException("Incomplete VLESS configuration");
            }
            Metrics.counter("vpn_provider_operation_total", "provider", "3x_ui", "operation", "provision", "result", "success")
                    .increment();
            return new ProvisionedVpnAccess(
                    PROVIDER_NAME, client.id(), configuration,
                    java.time.Instant.ofEpochMilli(client.expiryTime()));
        } catch (RuntimeException exception) {
            throw new ThreeXUiUncertainException();
        }
    }
}
