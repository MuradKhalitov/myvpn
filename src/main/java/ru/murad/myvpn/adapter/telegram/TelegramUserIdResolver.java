package ru.murad.myvpn.adapter.telegram;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.murad.myvpn.exception.TelegramUserNotFoundException;
import ru.murad.myvpn.repository.TelegramUserRepository;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class TelegramUserIdResolver {

    private final TelegramUserRepository userRepository;

    @Transactional(readOnly = true)
    public UUID resolve(long telegramId) {
        return userRepository.findByTelegramId(telegramId)
                .map(user -> user.getId())
                .orElseThrow(() -> new TelegramUserNotFoundException(telegramId));
    }
}
