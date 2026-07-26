package ru.murad.myvpn.client;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

class FakeVpnProviderActivationTest {
    private final FakeVpnProvider provider = new FakeVpnProvider();

    @Test
    void provisionIsStableForTheSameExternalIdentityAndAbsoluteExpiry() {
        UUID userId = UUID.randomUUID();
        Instant expiry = Instant.parse("2026-08-01T00:00:00Z");
        VpnProvisionRequest request = new VpnProvisionRequest(userId, 1L, expiry, userId.toString());
        ProvisionedVpnAccess first = provider.provision(request);
        ProvisionedVpnAccess second = provider.provision(request);
        assertThat(second.externalAccessId()).isEqualTo(first.externalAccessId());
        assertThat(second.targetExpiresAt()).isEqualTo(expiry);
        assertThat(provider.extend(new VpnExtensionRequest(first.externalAccessId(), expiry)).externalAccessId())
                .isEqualTo(first.externalAccessId());
    }

    @Test
    void provisionUsesStableIdentityInsteadOfGeneratingRetryIdentity() {
        String identity = "stable-client";
        ProvisionedVpnAccess result = provider.provision(new VpnProvisionRequest(
                UUID.randomUUID(), 1L, Instant.parse("2026-08-01T00:00:00Z"), identity));
        assertThat(result.externalAccessId()).isEqualTo(identity);
    }

    @Test
    void provisionPreservesAbsoluteTargetExpiry() {
        Instant target = Instant.parse("2026-08-01T00:00:00Z");
        assertThat(provider.provision(new VpnProvisionRequest(UUID.randomUUID(), 1L, target, "client"))
                .targetExpiresAt()).isEqualTo(target);
    }

    @Test
    void provisionIsIdempotentAcrossEquivalentRequests() {
        UUID subscription = UUID.randomUUID();
        Instant target = Instant.parse("2026-08-01T00:00:00Z");
        ProvisionedVpnAccess first = provider.provision(new VpnProvisionRequest(subscription, 1L, target, "client"));
        ProvisionedVpnAccess second = provider.provision(new VpnProvisionRequest(subscription, 1L, target, "client"));
        assertThat(second).isEqualTo(first);
    }

    @Test
    void extendPreservesIdentity() {
        ProvisionedVpnAccess result = provider.extend(new VpnExtensionRequest("client", Instant.parse("2026-08-02T00:00:00Z")));
        assertThat(result.externalAccessId()).isEqualTo("client");
    }

    @Test
    void extendUsesAbsoluteTargetExpiry() {
        Instant target = Instant.parse("2026-08-02T00:00:00Z");
        assertThat(provider.extend(new VpnExtensionRequest("client", target)).targetExpiresAt()).isEqualTo(target);
    }

    @Test
    void repeatedExtendWithSameTargetIsIdempotent() {
        Instant target = Instant.parse("2026-08-02T00:00:00Z");
        assertThat(provider.extend(new VpnExtensionRequest("client", target)))
                .isEqualTo(provider.extend(new VpnExtensionRequest("client", target)));
    }

    @Test
    void extendDoesNotAddDurationToTheTarget() {
        Instant target = Instant.parse("2026-08-02T00:00:00Z");
        assertThat(provider.extend(new VpnExtensionRequest("client", target)).targetExpiresAt())
                .isNotEqualTo(target.plusSeconds(30L * 86400));
    }

    @Test
    void unknownIdentityIsReturnedAsStableIdentityByFakeContract() {
        assertThat(provider.extend(new VpnExtensionRequest("unknown", Instant.MAX)).externalAccessId())
                .isEqualTo("unknown");
    }

    @Test
    void configurationContainsStableIdentityForProvision() {
        ProvisionedVpnAccess result = provider.provision(new VpnProvisionRequest(
                UUID.randomUUID(), 1L, Instant.parse("2026-08-01T00:00:00Z"), "client"));
        assertThat(result.configurationData()).isEqualTo("fake-vpn://client");
    }

    @Test
    void resultRenderingDoesNotExposeConfiguration() {
        ProvisionedVpnAccess result = provider.provision(new VpnProvisionRequest(
                UUID.randomUUID(), 1L, Instant.parse("2026-08-01T00:00:00Z"), "secret-client"));
        assertThat(result.toString()).doesNotContain("fake-vpn://secret-client", "secret-client");
    }

    @Test
    void concurrentProvisionKeepsOneStableIdentity() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            UUID subscription = UUID.randomUUID();
            VpnProvisionRequest request = new VpnProvisionRequest(subscription, 1L,
                    Instant.parse("2026-08-01T00:00:00Z"), "client");
            Future<ProvisionedVpnAccess> first = executor.submit(() -> provider.provision(request));
            Future<ProvisionedVpnAccess> second = executor.submit(() -> provider.provision(request));
            assertThat(first.get(5, TimeUnit.SECONDS).externalAccessId())
                    .isEqualTo(second.get(5, TimeUnit.SECONDS).externalAccessId());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentExtendKeepsIdentityAndTarget() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Instant target = Instant.parse("2026-08-02T00:00:00Z");
            Future<ProvisionedVpnAccess> first = executor.submit(() -> provider.extend(new VpnExtensionRequest("client", target)));
            Future<ProvisionedVpnAccess> second = executor.submit(() -> provider.extend(new VpnExtensionRequest("client", target)));
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(second.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }
}
