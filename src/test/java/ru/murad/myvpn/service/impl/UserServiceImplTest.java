package ru.murad.myvpn.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.murad.myvpn.application.account.AccountIdentityRegistrationService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");

    @Mock private TelegramUserRepository userRepository;
    @Mock private TelegramUserMapper userMapper;
    @Mock private AccountIdentityRegistrationService registrationService;

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl(
                userRepository,
                userMapper,
                registrationService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void delegatesNewUserRegistrationToAtomicAccountFoundationService() {
        RegisterTelegramUserRequest request =
                new RegisterTelegramUserRequest(
                        1001L, 2001L, "new_user", "New", "User");
        UUID accountId = UUID.randomUUID();
        TelegramUser user = user(accountId, request, NOW, NOW, UserRole.USER);
        TelegramUserDto expected = dto(accountId, request, NOW, NOW, UserRole.USER);
        when(registrationService.registerTelegramUser(request, NOW)).thenReturn(user);
        when(userMapper.toDto(user)).thenReturn(expected);

        TelegramUserDto result = userService.register(request);

        verify(registrationService).registerTelegramUser(request, NOW);
        assertThat(result).isSameAs(expected);
    }

    @Test
    void delegatesExistingProfileUpdateWithoutChangingDtoContract() {
        UUID userId = UUID.randomUUID();
        Instant createdAt = NOW.minusSeconds(3600);
        RegisterTelegramUserRequest request =
                new RegisterTelegramUserRequest(
                        1001L, 2001L, "new_user", "New", "Name");
        TelegramUser existingUser = user(
                userId, request, createdAt, NOW, UserRole.ADMIN);
        TelegramUserDto expected = dto(
                userId, request, createdAt, NOW, UserRole.ADMIN);
        when(registrationService.registerTelegramUser(request, NOW))
                .thenReturn(existingUser);
        when(userMapper.toDto(existingUser)).thenReturn(expected);

        TelegramUserDto result = userService.register(request);

        verify(registrationService).registerTelegramUser(request, NOW);
        assertThat(result).isSameAs(expected);
    }

    @Test
    void findByTelegramIdRemainsReadOnlyRepositoryLookup() {
        RegisterTelegramUserRequest request =
                new RegisterTelegramUserRequest(1001L, 2001L, null, null, null);
        TelegramUser user = user(
                UUID.randomUUID(), request, NOW, NOW, UserRole.USER);
        TelegramUserDto expected = dto(
                user.getId(), request, NOW, NOW, UserRole.USER);
        when(userRepository.findByTelegramId(1001L))
                .thenReturn(Optional.of(user));
        when(userMapper.toDto(user)).thenReturn(expected);

        assertThat(userService.findByTelegramId(1001L)).contains(expected);

        verifyNoInteractions(registrationService);
    }

    private TelegramUser user(
            UUID id,
            RegisterTelegramUserRequest request,
            Instant createdAt,
            Instant updatedAt,
            UserRole role
    ) {
        return TelegramUser.builder()
                .id(id)
                .telegramId(request.telegramId())
                .chatId(request.chatId())
                .username(request.username())
                .firstName(request.firstName())
                .lastName(request.lastName())
                .role(role)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
    }

    private TelegramUserDto dto(
            UUID id,
            RegisterTelegramUserRequest request,
            Instant createdAt,
            Instant updatedAt,
            UserRole role
    ) {
        return new TelegramUserDto(
                id,
                request.telegramId(),
                request.chatId(),
                request.username(),
                request.firstName(),
                request.lastName(),
                role,
                createdAt,
                updatedAt
        );
    }
}
