package ru.murad.myvpn.application.auth;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import ru.murad.myvpn.support.AuthIntegrationTestSupport;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

class EmailOtpConcurrencyIntegrationTest extends AuthIntegrationTestSupport {

    @Autowired
    private EmailOtpRequestService requestService;

    @Autowired
    private EmailOtpVerifyService verifyService;

    @Test
    void onlyOneConcurrentVerifyConsumesOtpAndCreatesSession() throws Exception {
        requestService.request("race@example.com");
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendOtp(any(), codeCaptor.capture(), any());
        String code = codeCaptor.getValue();
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> verify = () -> {
            start.await();
            try {
                verifyService.verify("race@example.com", code);
                return true;
            } catch (InvalidAuthenticationException exception) {
                return false;
            }
        };

        var executor = Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<Boolean>> results = List.of(
                    executor.submit(verify), executor.submit(verify));
            start.countDown();
            assertThat(results).extracting(result -> result.get()).containsExactlyInAnyOrder(true, false);
        } finally {
            executor.shutdownNow();
        }
        assertThat(sessionRepository.count()).isEqualTo(1);
        assertThat(accountRepository.count()).isEqualTo(1);
    }
}
