package ru.murad.myvpn.service;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import ru.murad.myvpn.dto.ActivateSubscriptionRequest;
import ru.murad.myvpn.dto.SubscriptionDto;

import java.util.Optional;

public interface SubscriptionService {

    SubscriptionDto activate(@Valid ActivateSubscriptionRequest request);

    Optional<SubscriptionDto> findCurrent(@Positive long userTelegramId);
}
