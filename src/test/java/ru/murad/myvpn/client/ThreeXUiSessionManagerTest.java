package ru.murad.myvpn.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ThreeXUiSessionManagerTest {

    @Test
    void shouldReuseOneCookieForParallelRequests() throws Exception {
        ThreeXUiAuthClient authClient = mock(ThreeXUiAuthClient.class);
        when(authClient.login(org.mockito.ArgumentMatchers.any()))
                .thenReturn("test-session=test-value");
        ThreeXUiSessionManager manager = new ThreeXUiSessionManager(authClient);
        CountDownLatch start = new CountDownLatch(1);
        List<String> cookies = new ArrayList<>();

        var executor = Executors.newFixedThreadPool(8);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<String>>();
            for (int index = 0; index < 20; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return manager.getSessionCookie(new ThreeXUiRequestBudget(1));
                }));
            }
            start.countDown();
            for (var future : futures) {
                cookies.add(future.get());
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(cookies).containsOnly("test-session=test-value");
        verify(authClient, times(1)).login(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldNotInvalidateAReplacedCookie() {
        ThreeXUiAuthClient authClient = mock(ThreeXUiAuthClient.class);
        when(authClient.login(org.mockito.ArgumentMatchers.any()))
                .thenReturn("test-session=first")
                .thenReturn("test-session=second");
        ThreeXUiSessionManager manager = new ThreeXUiSessionManager(authClient);
        ThreeXUiRequestBudget budget = new ThreeXUiRequestBudget(2);
        String first = manager.getSessionCookie(budget);
        manager.invalidate(first);
        String second = manager.getSessionCookie(budget);

        manager.invalidate(first);

        assertThat(manager.getSessionCookie(budget)).isEqualTo(second);
        verify(authClient, times(2)).login(org.mockito.ArgumentMatchers.any());
    }
}
