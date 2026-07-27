package ru.murad.myvpn.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.Metrics;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.murad.myvpn.config.PaymentProperties;
import ru.murad.myvpn.service.PaymentActivationService;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentActivationScheduler {
    private final PaymentActivationService activationService;
    private final PaymentProperties properties;

    @Scheduled(fixedDelayString = "${payment.activation.fixed-delay:10s}")
    public void process() {
        if (properties.activation().enabled()) {
            long started = System.nanoTime();
            var result = activationService.processPendingActivations(properties.activation().batchSize());
            Metrics.counter("payment_activation_success_total").increment(result.succeeded());
            Metrics.counter("payment_activation_retry_total").increment(result.retryScheduled());
            Metrics.counter("payment_activation_manual_review_total").increment(result.manualReview());
            Metrics.timer("scheduler_duration_seconds", "operation", "payment_activation")
                    .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
            log.info("payment activation batch claimed={} succeeded={} retryScheduled={} manualReview={} skipped={} infrastructureFailures={} durationMs={}",
                    result.claimed(), result.succeeded(), result.retryScheduled(), result.manualReview(),
                    result.skipped(), result.infrastructureFailures(),
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
    }
}
