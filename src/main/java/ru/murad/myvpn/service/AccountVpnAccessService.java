package ru.murad.myvpn.service;

import java.util.UUID;

/** Account-only use case; identity and delivery channels are intentionally absent. */
public interface AccountVpnAccessService {
    UUID ensureFreeVpnAccess(UUID accountId);
}
