package ru.murad.myvpn.adapter.telegram;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.murad.myvpn.exception.TelegramUserNotFoundException;
import ru.murad.myvpn.model.TelegramUser;
import ru.murad.myvpn.repository.TelegramUserRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelegramUserIdResolverTest {

    @Mock private TelegramUserRepository userRepository;

    @Test
    void resolvesInternalUserIdWithoutCreatingUser() {
        long telegramId = 123456L;
        UUID userId = UUID.randomUUID();
        when(userRepository.findByTelegramId(telegramId))
                .thenReturn(Optional.of(TelegramUser.builder().id(userId).build()));

        assertThat(new TelegramUserIdResolver(userRepository).resolve(telegramId))
                .isEqualTo(userId);

        verify(userRepository).findByTelegramId(telegramId);
    }

    @Test
    void rejectsUnknownTelegramUserUsingExistingNotFoundException() {
        long telegramId = 654321L;

        assertThatThrownBy(() -> new TelegramUserIdResolver(userRepository)
                .resolve(telegramId))
                .isInstanceOf(TelegramUserNotFoundException.class);

        verify(userRepository).findByTelegramId(telegramId);
    }
}
