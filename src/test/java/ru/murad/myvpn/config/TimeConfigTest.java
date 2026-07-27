package ru.murad.myvpn.config;

import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class TimeConfigTest {

    @Test
    void applicationClockIsExplicitlyUtc() {
        assertThat(new TimeConfig().clock().getZone()).isEqualTo(ZoneOffset.UTC);
    }
}
