package ru.murad.myvpn.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AndroidReleasePropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues(
                    "app.android.latest-version-code=1",
                    "app.android.latest-version-name=1.0.0",
                    "app.android.minimum-supported-version-code=1",
                    "app.android.apk-url=https://api.myvpn05.ru/downloads/myvpn-1.0.0.apk",
                    "app.android.changelog=Первая публичная версия MyVPN");

    @Test
    void bindsAndroidReleaseMetadata() {
        contextRunner.run(context -> {
            AndroidReleaseProperties properties = context.getBean(AndroidReleaseProperties.class);
            assertThat(properties.latestVersionCode()).isEqualTo(1);
            assertThat(properties.latestVersionName()).isEqualTo("1.0.0");
            assertThat(properties.minimumSupportedVersionCode()).isEqualTo(1);
            assertThat(properties.apkUrl().toString()).isEqualTo("https://api.myvpn05.ru/downloads/myvpn-1.0.0.apk");
        });
    }

    @Configuration
    @EnableConfigurationProperties(AndroidReleaseProperties.class)
    static class PropertiesConfiguration { }
}
