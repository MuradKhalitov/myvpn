package ru.murad.myvpn.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

import java.util.List;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "vpn.provider.type", havingValue = "3x-ui")
public class ThreeXUiVpnProvider implements VpnProvider {

    private static final String PROVIDER_NAME = "3X_UI";
    private static final String EMAIL_PREFIX = "myvpn-";

    private final ThreeXUiInboundClient inboundClient;
    private final VpnConfigurationFactory configurationFactory;
    private final ThreeXUiProperties properties;

    public ThreeXUiVpnProvider(
            ThreeXUiInboundClient inboundClient,
            VpnConfigurationFactory configurationFactory,
            ThreeXUiProperties properties
    ) {
        this.inboundClient = inboundClient;
        this.configurationFactory = configurationFactory;
        this.properties = properties;
    }

    @Override
    public ProvisionedVpnAccess provision(VpnProvisionRequest request) {
        ThreeXUiRequestBudget budget =
                new ThreeXUiRequestBudget(properties.maxRequestsPerOperation());
        String clientUuid = request.subscriptionId().toString();
        ThreeXUiVlessClient client = ThreeXUiVlessClient.create(
                clientUuid, EMAIL_PREFIX + clientUuid, request.expiresAt().toEpochMilli());
        for (int attempt = 1; attempt <= properties.maxMutationAttempts(); attempt++) {
            boolean mutationAttempted = false;
            try {
                ThreeXUiInboundResponse inbound = inboundClient.getInbound(budget);
                Optional<ThreeXUiVlessClient> existing = findClient(inbound, clientUuid);
                if (existing.isPresent()) {
                    return result(inbound, existing.get());
                }
                mutationAttempted = true;
                budget.reserveReconciliation();
                inboundClient.addClient(request(client), budget);
                ThreeXUiInboundResponse confirmed =
                        inboundClient.getInboundForReconciliation(budget);
                Optional<ThreeXUiVlessClient> confirmedClient =
                        findClient(confirmed, clientUuid);
                if (confirmedClient.isPresent()) {
                    return result(confirmed, confirmedClient.get());
                }
            } catch (ThreeXUiRetryableException exception) {
                if (mutationAttempted) {
                    try {
                        ThreeXUiInboundResponse recovered =
                                inboundClient.getInboundForReconciliation(budget);
                        Optional<ThreeXUiVlessClient> recoveredClient =
                                findClient(recovered, clientUuid);
                        if (recoveredClient.isPresent()) {
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
        throw new ThreeXUiException("3x-ui client creation was not confirmed");
    }

    @Override
    public void extend(VpnExtensionRequest request) {
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
                    return;
                }
                ThreeXUiVlessClient updated = existing.withExpiryTime(expectedExpiry);
                budget.reserveReconciliation();
                inboundClient.updateClient(
                        request.externalAccessId(), request(updated), budget);
                if (isExpiryApplied(
                        request.externalAccessId(), expectedExpiry, budget, true)) {
                    return;
                }
            } catch (ThreeXUiRetryableException exception) {
                try {
                    if (isExpiryApplied(request.externalAccessId(),
                            request.expiresAt().toEpochMilli(), budget, true)) {
                        return;
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

    private Optional<ThreeXUiVlessClient> findClient(
            ThreeXUiInboundResponse inbound,
            String clientUuid
    ) {
        return inboundClient.parseSettings(inbound).clients().stream()
                .filter(client -> clientUuid.equals(client.id()))
                .findFirst();
    }

    private ProvisionedVpnAccess result(
            ThreeXUiInboundResponse inbound,
            ThreeXUiVlessClient client
    ) {
        return new ProvisionedVpnAccess(
                PROVIDER_NAME,
                client.id(),
                configurationFactory.create(inbound, client).orElse(null));
    }
}
