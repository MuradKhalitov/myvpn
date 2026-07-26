package ru.murad.myvpn.scheduler;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import ru.murad.myvpn.config.VpnDeliveryProperties;
import ru.murad.myvpn.service.VpnDeliveryService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class VpnDeliverySchedulerContextTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfiguration.class)
            .withBean(VpnDeliveryService.class, () -> mock(VpnDeliveryService.class))
            .withPropertyValues(baseProperties());

    @Test void absentAndExplicitFalseDoNotCreateScheduler() {
        context.run(it -> assertThat(it).doesNotHaveBean(VpnDeliveryScheduler.class));
        context.withPropertyValues("vpn.delivery.enabled=false").run(it ->
                assertThat(it).doesNotHaveBean(VpnDeliveryScheduler.class));
    }

    @Test void enabledCreatesSchedulerAndRegistersScheduledTask() {
        context.withPropertyValues("vpn.delivery.enabled=true").run(it -> {
            assertThat(it).hasSingleBean(VpnDeliveryScheduler.class);
            assertThat(it).hasSingleBean(ScheduledTaskHolder.class);
            assertThat(it.getBean(ScheduledTaskHolder.class).getScheduledTasks()).isNotEmpty();
        });
    }

    @Test void invalidPropertyMatrixFailsStartup() {
        String[] invalid = {"vpn.delivery.batch-size=0", "vpn.delivery.batch-size=-1", "vpn.delivery.fixed-delay=0s",
                "vpn.delivery.fixed-delay=-1s", "vpn.delivery.lease-duration=0s", "vpn.delivery.max-attempts=0",
                "vpn.delivery.retry-initial-delay=-1s", "vpn.delivery.retry-max-delay=1s", "vpn.delivery.retry-initial-delay=5s"};
        for (String value : invalid) {
            ApplicationContextRunner candidate = context.withPropertyValues("vpn.delivery.enabled=true", value);
            if ("vpn.delivery.retry-max-delay=1s".equals(value)) candidate = candidate.withPropertyValues("vpn.delivery.retry-initial-delay=5s");
            if ("vpn.delivery.retry-initial-delay=5s".equals(value)) continue;
            candidate.run(it -> assertThat(it.getStartupFailure()).isNotNull());
        }
    }

    private static String[] baseProperties() {
        return new String[]{"vpn.delivery.batch-size=10", "vpn.delivery.fixed-delay=5s", "vpn.delivery.lease-duration=2m",
                "vpn.delivery.max-attempts=5", "vpn.delivery.retry-initial-delay=5s", "vpn.delivery.retry-max-delay=5m"};
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @EnableConfigurationProperties(VpnDeliveryProperties.class)
    @Import(VpnDeliveryScheduler.class)
    static class SchedulingConfiguration {
    }
}
