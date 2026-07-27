package ru.murad.myvpn.scheduler;

import org.junit.jupiter.api.Test;
import ru.murad.myvpn.config.PaymentProperties;
import ru.murad.myvpn.model.PaymentProviderType;
import ru.murad.myvpn.service.PaymentActivationService;
import ru.murad.myvpn.service.PaymentActivationWorkerResult;
import java.net.URI;
import java.time.Duration;
import static org.mockito.Mockito.*;

class PaymentActivationSchedulerTest {
    private final PaymentActivationService worker = mock(PaymentActivationService.class);

    @Test
    void disabledFlagDoesNotInvokeWorker() {
        new PaymentActivationScheduler(worker, properties(false)).process();
        verifyNoInteractions(worker);
    }

    @Test
    void enabledFlagInvokesWorkerWithConfiguredBatchSize() {
        when(worker.processPendingActivations(7))
                .thenReturn(new PaymentActivationWorkerResult(0, 0, 0, 0, 0, 0, 0));
        new PaymentActivationScheduler(worker, properties(true)).process();
        verify(worker).processPendingActivations(7);
    }

    private PaymentProperties properties(boolean enabled) {
        return new PaymentProperties(PaymentProviderType.FAKE, Duration.ofHours(1),
                URI.create("https://example.invalid"), true,
                new PaymentProperties.Verification(Duration.ofSeconds(1), Duration.ZERO),
                new PaymentProperties.Activation(enabled, Duration.ofSeconds(1), 7,
                        Duration.ofMinutes(1), Duration.ofSeconds(1), 3));
    }
}
