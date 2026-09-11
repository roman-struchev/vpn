package com.vpn.server;

import com.vpn.server.dto.AuthResponse;
import com.vpn.server.repository.UserRepository;
import com.vpn.server.service.DeviceAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end regression test for the create-race that produced an unhandled
 * 500 in production: POST /api/v1/auth/device fired twice in quick
 * succession for the same brand-new deviceUuid both saw "no user found" and
 * both tried to INSERT a User row with the same derived email, and the loser
 * blew up on the users_email_key unique constraint instead of just getting
 * back the winner's session.
 *
 * Runs against the real Spring context / H2 test datasource (see
 * application-test.yml) with real threads and a real connection pool, so it
 * exercises real transaction commit/rollback timing rather than mocked
 * repository behavior - see DeviceAuthServiceTest for a deterministic,
 * non-flaky unit test of the same catch-and-retry logic in isolation.
 */
@SpringBootTest
@ActiveProfiles("test")
class DeviceAuthConcurrencyTest {

    @Autowired
    private DeviceAuthService deviceAuthService;

    @Autowired
    private UserRepository userRepository;

    @Test
    void concurrentFirstTimeLoginsForSameDeviceUuidResolveToOneUserWithoutErrors() throws Exception {
        String deviceUuid = UUID.randomUUID().toString();
        int threadCount = 8;

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            CountDownLatch ready = new CountDownLatch(threadCount);
            CountDownLatch start = new CountDownLatch(1);

            List<Future<AuthResponse>> futures = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    // Every thread races to authenticate the SAME never-before-seen
                    // deviceUuid, as if a client fired the device-login call
                    // multiple times before the first one committed.
                    return deviceAuthService.authenticateDevice(deviceUuid, null);
                }));
            }

            ready.await(5, TimeUnit.SECONDS);
            start.countDown();

            Set<Long> userIds = new HashSet<>();
            for (Future<AuthResponse> future : futures) {
                // .get() rethrows any exception a thread hit - if the create-race
                // regresses to throwing DataIntegrityViolationException instead of
                // recovering, this fails the test.
                AuthResponse resp = future.get(15, TimeUnit.SECONDS);
                userIds.add(resp.userId());
            }

            assertEquals(1, userIds.size(),
                    "all concurrent first-time device logins for the same deviceUuid must resolve to a single user, got: " + userIds);

            long persistedRows = userRepository.findAll().stream()
                    .filter(u -> deviceUuid.equals(u.getDeviceUuid()))
                    .count();
            assertEquals(1, persistedRows, "exactly one User row should have been persisted for this deviceUuid");
        } finally {
            pool.shutdownNow();
        }
    }
}
