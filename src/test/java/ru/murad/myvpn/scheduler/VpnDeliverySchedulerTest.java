package ru.murad.myvpn.scheduler;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import ru.murad.myvpn.config.VpnDeliveryProperties;
import ru.murad.myvpn.service.VpnDeliveryService;
import java.lang.reflect.Method;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class VpnDeliverySchedulerTest {
    @Test void schedulerIsOptInNonTransactionalAndDelegatesOneBatch() throws Exception {
        ConditionalOnProperty condition = VpnDeliveryScheduler.class.getAnnotation(ConditionalOnProperty.class);
        Method method = VpnDeliveryScheduler.class.getMethod("process");
        assertThat(condition.name()).containsExactly("vpn.delivery.enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(method.getAnnotation(Scheduled.class)).isNotNull();
        assertThat(method.getAnnotation(Transactional.class)).isNull();
        VpnDeliveryService service = mock(VpnDeliveryService.class);
        new VpnDeliveryScheduler(service, new VpnDeliveryProperties(true, 7, Duration.ofSeconds(5), Duration.ofMinutes(2), 5, Duration.ofSeconds(5), Duration.ofMinutes(5))).process();
        verify(service).processPendingDeliveries(7);
    }
}
