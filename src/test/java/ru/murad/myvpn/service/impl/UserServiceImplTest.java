package ru.murad.myvpn.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.murad.myvpn.dto.RegisterTelegramUserRequest;
import ru.murad.myvpn.dto.TelegramUserDto;
import ru.murad.myvpn.mapper.TelegramUserMapper;
import ru.murad.myvpn.model.TelegramUser;
import ru.murad.myvpn.model.UserRole;
import ru.murad.myvpn.repository.TelegramUserRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");

    @Mock
    private TelegramUserRepository userRepository;

    @Mock
    private TelegramUserMapper userMapper;

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        userService = new UserServiceImpl(userRepository, userMapper, clock);
        when(userRepository.save(any(TelegramUser.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void shouldRegisterNewUser() {
        RegisterTelegramUserRequest request =
                new RegisterTelegramUserRequest(1001L, 2001L, "new_user", "New", "User");
        TelegramUserDto expected = dto(UUID.randomUUID(), request, NOW, NOW);
        when(userRepository.findByTelegramId(1001L)).thenReturn(Optional.empty());
        when(userMapper.toDto(any(TelegramUser.class))).thenReturn(expected);

        TelegramUserDto result = userService.register(request);

        ArgumentCaptor<TelegramUser> captor = ArgumentCaptor.forClass(TelegramUser.class);
        verify(userRepository).save(captor.capture());
        TelegramUser savedUser = captor.getValue();
        assertThat(savedUser.getId()).isNotNull();
        assertThat(savedUser.getTelegramId()).isEqualTo(1001L);
        assertThat(savedUser.getChatId()).isEqualTo(2001L);
        assertThat(savedUser.getRole()).isEqualTo(UserRole.USER);
        assertThat(savedUser.getCreatedAt()).isEqualTo(NOW);
        assertThat(savedUser.getUpdatedAt()).isEqualTo(NOW);
        assertThat(result).isSameAs(expected);
    }

    @Test
    void shouldUpdateProfileWhenUserAlreadyExists() {
        UUID userId = UUID.randomUUID();
        Instant createdAt = NOW.minusSeconds(3600);
        TelegramUser existingUser = TelegramUser.builder()
                .id(userId)
                .telegramId(1001L)
                .chatId(2000L)
                .username("old_user")
                .firstName("Old")
                .lastName("Name")
                .role(UserRole.ADMIN)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
        RegisterTelegramUserRequest request =
                new RegisterTelegramUserRequest(1001L, 2001L, "new_user", "New", "Name");
        TelegramUserDto expected = dto(userId, request, createdAt, NOW);
        when(userRepository.findByTelegramId(1001L)).thenReturn(Optional.of(existingUser));
        when(userMapper.toDto(existingUser)).thenReturn(expected);

        TelegramUserDto result = userService.register(request);

        verify(userRepository).save(existingUser);
        assertThat(existingUser.getId()).isEqualTo(userId);
        assertThat(existingUser.getChatId()).isEqualTo(2001L);
        assertThat(existingUser.getUsername()).isEqualTo("new_user");
        assertThat(existingUser.getFirstName()).isEqualTo("New");
        assertThat(existingUser.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(existingUser.getCreatedAt()).isEqualTo(createdAt);
        assertThat(existingUser.getUpdatedAt()).isEqualTo(NOW);
        assertThat(result).isSameAs(expected);
    }

    private TelegramUserDto dto(
            UUID id,
            RegisterTelegramUserRequest request,
            Instant createdAt,
            Instant updatedAt
    ) {
        return new TelegramUserDto(
                id,
                request.telegramId(),
                request.chatId(),
                request.username(),
                request.firstName(),
                request.lastName(),
                UserRole.USER,
                createdAt,
                updatedAt
        );
    }
}
