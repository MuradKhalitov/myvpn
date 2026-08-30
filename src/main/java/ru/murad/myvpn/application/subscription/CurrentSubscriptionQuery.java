package ru.murad.myvpn.application.subscription;

import java.util.Optional;
import java.util.UUID;

public interface CurrentSubscriptionQuery {

    Optional<CurrentSubscriptionView> findCurrent(UUID userId);
}
