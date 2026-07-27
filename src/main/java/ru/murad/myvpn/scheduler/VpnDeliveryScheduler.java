package ru.murad.myvpn.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.Metrics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.murad.myvpn.config.VpnDeliveryProperties;
import ru.murad.myvpn.service.VpnDeliveryService;

@Component @RequiredArgsConstructor @Slf4j @ConditionalOnProperty(name = "vpn.delivery.enabled", havingValue = "true")
public class VpnDeliveryScheduler {
    private final VpnDeliveryService service; private final VpnDeliveryProperties properties;
    @Scheduled(fixedDelayString = "${vpn.delivery.fixed-delay:5s}") public void process() {
        long started = System.nanoTime();
        var result = service.processPendingDeliveries(properties.batchSize());
        Metrics.counter("vpn_delivery_success_total").increment(result.delivered());
        Metrics.counter("vpn_delivery_retry_total").increment(result.retryScheduled());
        Metrics.counter("vpn_delivery_manual_review_total").increment(result.manualReview());
        Metrics.timer("scheduler_duration_seconds", "operation", "vpn_delivery")
                .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
        log.info("vpn delivery batch claimed={} succeeded={} retryScheduled={} manualReview={} skipped={} infrastructureFailures={} durationMs={}",
                result.claimed(), result.delivered(), result.retryScheduled(), result.manualReview(),
                result.skipped(), result.infrastructureFailures(),
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }
}
