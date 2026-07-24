package ru.murad.myvpn.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import ru.murad.myvpn.dto.RegisterTelegramUserRequest;
import ru.murad.myvpn.dto.TelegramUserDto;
import ru.murad.myvpn.mapper.TelegramUserMapper;
import ru.murad.myvpn.model.TelegramUser;
import ru.murad.myvpn.model.UserRole;
import ru.murad.myvpn.repository.TelegramUserRepository;
import ru.murad.myvpn.service.UserService;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.Optional;

@Service
@Validated
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final TelegramUserRepository userRepository;
    private final TelegramUserMapper userMapper;
    private final Clock clock;

    @Override
    @Transactional
    public TelegramUserDto register(RegisterTelegramUserRequest request) {
        Instant now = clock.instant();

        TelegramUser user = userRepository.findByTelegramId(request.telegramId())
                .map(existingUser -> updateExistingUser(existingUser, request, now))
                .orElseGet(() -> createUser(request, now));

        return userMapper.toDto(userRepository.save(user));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TelegramUserDto> findByTelegramId(long telegramId) {
        return userRepository.findByTelegramId(telegramId).map(userMapper::toDto);
    }

    private TelegramUser createUser(RegisterTelegramUserRequest request, Instant now) {
        return TelegramUser.builder()
                .id(UUID.randomUUID())
                .telegramId(request.telegramId())
                .chatId(request.chatId())
                .username(request.username())
                .firstName(request.firstName())
                .lastName(request.lastName())
                .role(UserRole.USER)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private TelegramUser updateExistingUser(
            TelegramUser user,
            RegisterTelegramUserRequest request,
            Instant now
    ) {
        user.updateProfile(
                request.chatId(),
                request.username(),
                request.firstName(),
                request.lastName(),
                now
        );
        return user;
    }
}
