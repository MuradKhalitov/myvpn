package ru.murad.myvpn.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.murad.myvpn.config.TelegramProperties;
import ru.murad.myvpn.exception.AdministratorAccessDeniedException;
import ru.murad.myvpn.service.AdminAuthorizationService;

@Service
@RequiredArgsConstructor
public class AdminAuthorizationServiceImpl implements AdminAuthorizationService {

    private final TelegramProperties telegramProperties;

    @Override
    public void checkAccess(long telegramId) {
        if (!telegramProperties.adminIds().contains(telegramId)) {
            throw new AdministratorAccessDeniedException();
        }
    }
}
