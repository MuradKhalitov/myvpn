package ru.murad.myvpn.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.murad.myvpn.config.PaymentProperties;
import ru.murad.myvpn.service.PaymentActivationService;

@Component
@RequiredArgsConstructor
public class PaymentActivationScheduler {
    private final PaymentActivationService activationService;
    private final PaymentProperties properties;

    @Scheduled(fixedDelayString = "${payment.activation.fixed-delay:10s}")
    public void process() {
        if (properties.activation().enabled()) {
            activationService.processPendingActivations(properties.activation().batchSize());
        }
    }
}
