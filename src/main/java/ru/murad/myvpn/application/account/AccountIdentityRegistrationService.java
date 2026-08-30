package ru.murad.myvpn.application.account;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.murad.myvpn.dto.RegisterTelegramUserRequest;
import ru.murad.myvpn.model.Account;
import ru.murad.myvpn.model.AccountIdentity;
import ru.murad.myvpn.model.AccountIdentityType;
import ru.murad.myvpn.model.AccountStatus;
import ru.murad.myvpn.model.TelegramUser;
import ru.murad.myvpn.model.UserRole;
import ru.murad.myvpn.repository.AccountIdentityRepository;
import ru.murad.myvpn.repository.AccountRepository;
import ru.murad.myvpn.repository.TelegramUserRepository;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountIdentityRegistrationService {

    private final AccountRepository accountRepository;
    private final AccountIdentityRepository identityRepository;
    private final TelegramUserRepository telegramUserRepository;

    @Transactional
    public TelegramUser registerTelegramUser(
            RegisterTelegramUserRequest request,
            Instant now
    ) {
        telegramUserRepository.acquireRegistrationLock(request.telegramId());

        return telegramUserRepository.findByTelegramId(request.telegramId())
                .map(user -> updateExistingUser(user, request, now))
                .orElseGet(() -> createUserFoundation(request, now));
    }

    private TelegramUser createUserFoundation(
            RegisterTelegramUserRequest request,
            Instant now
    ) {
        UUID accountId = UUID.randomUUID();
        Account account = Account.builder()
                .id(accountId)
                .status(AccountStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();
        accountRepository.saveAndFlush(account);

        TelegramUser user = TelegramUser.builder()
                .id(accountId)
                .telegramId(request.telegramId())
                .chatId(request.chatId())
                .username(request.username())
                .firstName(request.firstName())
                .lastName(request.lastName())
                .role(UserRole.USER)
                .createdAt(now)
                .updatedAt(now)
                .build();
        telegramUserRepository.saveAndFlush(user);

        String subject = Long.toString(request.telegramId());
        identityRepository.saveAndFlush(AccountIdentity.builder()
                .id(UUID.randomUUID())
                .account(account)
                .type(AccountIdentityType.TELEGRAM)
                .subject(subject)
                .normalizedSubject(subject)
                .verifiedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build());
        return user;
    }

    private TelegramUser updateExistingUser(
            TelegramUser user,
            RegisterTelegramUserRequest request,
            Instant now
    ) {
        Account account = accountRepository.findById(user.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Account foundation is missing for a Telegram user"));
        String subject = Long.toString(request.telegramId());
        AccountIdentity identity = identityRepository
                .findByTypeAndNormalizedSubject(
                        AccountIdentityType.TELEGRAM, subject)
                .orElseThrow(() -> new IllegalStateException(
                        "Telegram account identity is missing"));
        if (!identity.getAccount().getId().equals(account.getId())) {
            throw new IllegalStateException("Telegram account identity is inconsistent");
        }

        user.updateProfile(
                request.chatId(),
                request.username(),
                request.firstName(),
                request.lastName(),
                now
        );
        return telegramUserRepository.saveAndFlush(user);
    }
}
