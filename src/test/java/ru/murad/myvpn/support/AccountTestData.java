package ru.murad.myvpn.support;

import lombok.experimental.UtilityClass;
import ru.murad.myvpn.model.Account;
import ru.murad.myvpn.model.AccountStatus;
import ru.murad.myvpn.model.TelegramUser;
import ru.murad.myvpn.repository.AccountRepository;
import ru.murad.myvpn.repository.TelegramUserRepository;

@UtilityClass
public class AccountTestData {

    public TelegramUser saveTelegramUser(
            AccountRepository accountRepository,
            TelegramUserRepository userRepository,
            TelegramUser user
    ) {
        accountRepository.saveAndFlush(Account.builder()
                .id(user.getId())
                .status(AccountStatus.ACTIVE)
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build());
        return userRepository.saveAndFlush(user);
    }
}
