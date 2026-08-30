package ru.murad.myvpn.application.auth;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import ru.murad.myvpn.model.AccountIdentityType;
import ru.murad.myvpn.support.AuthIntegrationTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class EmailAccountRegistrationIntegrationTest extends AuthIntegrationTestSupport {

    @Autowired
    private EmailOtpRequestService requestService;

    @Autowired
    private EmailOtpVerifyService verifyService;

    @Test
    void createsAndroidOnlyAccountAndReusesItOnNextLogin() {
        requestService.request(" User@Example.com ");
        String firstCode = capturedCodes(1).getAllValues().get(0);
        verifyService.verify("user@example.com", firstCode);

        var identity = identityRepository.findByTypeAndNormalizedSubject(
                AccountIdentityType.EMAIL, "user@example.com").orElseThrow();
        var accountId = identity.getAccount().getId();
        assertThat(identity.getSubject()).isEqualTo("user@example.com");
        assertThat(identity.getVerifiedAt()).isNotNull();
        assertThat(telegramUserRepository.findById(accountId)).isEmpty();

        requestService.request("USER@example.com");
        String secondCode = capturedCodes(2).getAllValues().get(1);
        verifyService.verify("user@example.com", secondCode);

        assertThat(identityRepository.findByTypeAndNormalizedSubject(
                AccountIdentityType.EMAIL, "user@example.com").orElseThrow()
                .getAccount().getId()).isEqualTo(accountId);
        assertThat(accountRepository.count()).isEqualTo(1);
    }

    private ArgumentCaptor<String> capturedCodes(int invocations) {
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailSender, times(invocations)).sendOtp(any(), code.capture(), any());
        return code;
    }
}
