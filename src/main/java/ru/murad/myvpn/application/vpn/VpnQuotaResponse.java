package ru.murad.myvpn.application.vpn;

import java.time.Instant;

public record VpnQuotaResponse(long limitBytes, Instant periodStartedAt, Instant periodEndsAt) { }
