package ru.murad.myvpn.service;

import org.junit.jupiter.api.Test;
import ru.murad.myvpn.model.PaymentProviderType;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class PaymentActivationSnapshotSafetyTest {
    @Test
    void snapshotAndResultDoNotRenderSensitiveValues() {
        String token = UUID.randomUUID().toString();
        String external = "external-secret";
        String config = "vless://secret";
        PreparedPaymentActivation snapshot = new PreparedPaymentActivation(UUID.randomUUID(), UUID.randomUUID(),
                PaymentProviderType.FAKE, PaymentActivationAction.PROVISION, 1L, UUID.fromString(token), 30,
                Instant.parse("2026-08-01T00:00:00Z"), null, null, external, null, null);
        PaymentActivationResult result = new PaymentActivationResult(external, snapshot.targetExpiresAt(), config);
        assertThat(snapshot.toString()).doesNotContain(token, external);
        assertThat(result.toString()).doesNotContain(external, config);
    }

}
