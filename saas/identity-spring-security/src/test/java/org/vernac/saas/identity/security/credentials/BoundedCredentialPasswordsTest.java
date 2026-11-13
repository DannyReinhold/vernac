// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security.credentials;

import static org.junit.jupiter.api.Assertions.*;
import static org.vernac.saas.identity.security.credentials.CredentialConfigurationException.Code.*;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

class BoundedCredentialPasswordsTest {
    static final String INPUT = "controlled credential fixture 948765";
    static final BoundedCredentialPasswords.Limits DEFAULT =
            BoundedCredentialPasswords.Limits.defaults();

    static BoundedCredentialPasswords.Limits limits(int workers, int queue, long milliseconds) {
        return new BoundedCredentialPasswords.Limits(
                workers, queue, Duration.ofMillis(milliseconds));
    }

    static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() - deadline < 0) Thread.sleep(1);
        assertTrue(condition.getAsBoolean(), "bounded fixture condition");
    }

    static CredentialConfigurationException.Code code(Future<?> future) throws Exception {
        var error = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        return assertInstanceOf(CredentialConfigurationException.class, error.getCause()).code();
    }

    static class BlockingWork implements BoundedCredentialPasswords.Work {
        final AtomicInteger active = new AtomicInteger(),
                peak = new AtomicInteger(),
                calls = new AtomicInteger();
        final CountDownLatch started, release = new CountDownLatch(1);

        BlockingWork(int workers) {
            started = new CountDownLatch(workers);
        }

        @Override
        public String encode(String input) {
            calls.incrementAndGet();
            int running = active.incrementAndGet();
            peak.accumulateAndGet(running, Math::max);
            started.countDown();
            boolean interrupted = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            try {
                while (release.getCount() > 0) {
                    if (System.nanoTime() - deadline >= 0)
                        throw new IllegalStateException("fixture deadline");
                    try {
                        release.await(20, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                return "TEST-ONLY-ENCODING";
            } finally {
                active.decrementAndGet();
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        public boolean matches(String input, String stored) {
            encode(input);
            return false;
        }

        public void dummy(String input) {
            encode(input);
        }
    }

    @Test
    void defaultsAndConfigurationAreExplicitAndFinite() {
        assertEquals(4, DEFAULT.parallelism());
        assertEquals(16, DEFAULT.queueCapacity());
        assertEquals(Duration.ofMillis(250), DEFAULT.maximumAdmissionWait());
        for (int[] invalid :
                List.of(
                        new int[] {0, 1},
                        new int[] {65, 1},
                        new int[] {1, -1},
                        new int[] {1, 1025}))
            assertThrows(IllegalArgumentException.class, () -> limits(invalid[0], invalid[1], 250));
        for (Duration invalid :
                List.of(
                        Duration.ZERO,
                        Duration.ofSeconds(-1),
                        Duration.ofSeconds(6),
                        Duration.ofSeconds(Long.MAX_VALUE)))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new BoundedCredentialPasswords.Limits(1, 1, invalid));
        assertDoesNotThrow(() -> limits(1, 0, 1));
    }

    @Test
    void fixedWorkersAndQueueRejectOverflowWithoutCallerRunsOrExtraWork() throws Exception {
        var backend = new BlockingWork(2);
        try (var work = new BoundedCredentialPasswords(backend, limits(2, 2, 2000));
                var callers = Executors.newFixedThreadPool(4)) {
            try {
                var first = callers.submit(() -> work.encode(INPUT));
                var second = callers.submit(() -> work.encode(INPUT));
                assertTrue(backend.started.await(5, TimeUnit.SECONDS));
                var third = callers.submit(() -> work.encode(INPUT));
                var fourth = callers.submit(() -> work.encode(INPUT));
                await(() -> work.queuedWork() == 2);
                assertEquals(
                        WORK_CAPACITY,
                        assertThrows(
                                        CredentialConfigurationException.class,
                                        () -> work.encode(INPUT))
                                .code());
                assertEquals(2, backend.calls.get());
                assertEquals(2, backend.peak.get());
                assertEquals(2, work.queuedWork());
                backend.release.countDown();
                for (var result : List.of(first, second, third, fourth))
                    assertEquals("TEST-ONLY-ENCODING", result.get(5, TimeUnit.SECONDS));
                assertEquals(4, backend.calls.get());
                assertEquals(2, backend.peak.get());
                assertEquals("TEST-ONLY-ENCODING", work.encode(INPUT));
            } finally {
                backend.release.countDown();
            }
        }
    }

    @Test
    void queuedTimeoutRemovesCancelledWorkAndNeverExecutesItLater() throws Exception {
        var backend = new BlockingWork(1);
        try (var work = new BoundedCredentialPasswords(backend, limits(1, 1, 40));
                var callers = Executors.newFixedThreadPool(2)) {
            try {
                var running = callers.submit(() -> work.encode(INPUT));
                assertTrue(backend.started.await(5, TimeUnit.SECONDS));
                var expired = callers.submit(() -> work.encode("expired credential fixture 98765"));
                assertEquals(WORK_CAPACITY, code(expired));
                assertEquals(0, work.queuedWork());
                assertEquals(1, backend.calls.get());
                var another = callers.submit(() -> work.encode(INPUT));
                assertEquals(WORK_CAPACITY, code(another));
                backend.release.countDown();
                assertEquals("TEST-ONLY-ENCODING", running.get(5, TimeUnit.SECONDS));
                assertEquals("TEST-ONLY-ENCODING", work.encode(INPUT));
                assertEquals(2, backend.calls.get());
            } finally {
                backend.release.countDown();
            }
        }
    }

    @Test
    void interruptedCallerDoesNotRefundAStillRunningHashSlot() throws Exception {
        var backend = new BlockingWork(1);
        var outcome = new AtomicReference<CredentialConfigurationException.Code>();
        var restored = new AtomicBoolean();
        try (var work = new BoundedCredentialPasswords(backend, limits(1, 0, 1000))) {
            Thread caller =
                    Thread.ofPlatform()
                            .unstarted(
                                    () -> {
                                        try {
                                            work.encode(INPUT);
                                        } catch (CredentialConfigurationException interrupted) {
                                            outcome.set(interrupted.code());
                                            restored.set(Thread.currentThread().isInterrupted());
                                        }
                                    });
            try {
                caller.start();
                assertTrue(backend.started.await(5, TimeUnit.SECONDS));
                caller.interrupt();
                caller.join(5000);
                assertFalse(caller.isAlive());
                assertEquals(WORK_INTERRUPTED, outcome.get());
                assertTrue(restored.get());
                assertEquals(1, backend.active.get());
                assertEquals(
                        WORK_CAPACITY,
                        assertThrows(
                                        CredentialConfigurationException.class,
                                        () -> work.encode(INPUT))
                                .code());
                backend.release.countDown();
                await(() -> backend.active.get() == 0);
                // A synchronous handoff can briefly reject until the old worker returns to its
                // queue.
                await(
                        () -> {
                            try {
                                return work.encode(INPUT).equals("TEST-ONLY-ENCODING");
                            } catch (CredentialConfigurationException busy) {
                                assertEquals(WORK_CAPACITY, busy.code());
                                return false;
                            }
                        });
                assertEquals(1, backend.peak.get());
            } finally {
                backend.release.countDown();
                caller.interrupt();
                caller.join(5000);
            }
        }
    }

    @Test
    void closeCancelsQueuedWorkAndRetainsRealRunningCapacityUntilCompletion() throws Exception {
        var backend = new BlockingWork(1);
        try (var work = new BoundedCredentialPasswords(backend, limits(1, 1, 2000));
                var callers = Executors.newFixedThreadPool(2)) {
            try {
                var running = callers.submit(() -> work.encode(INPUT));
                assertTrue(backend.started.await(5, TimeUnit.SECONDS));
                var queued = callers.submit(() -> work.encode(INPUT));
                await(() -> work.queuedWork() == 1);
                work.close();
                assertEquals(WORK_CLOSED, code(queued));
                assertEquals(0, work.queuedWork());
                assertEquals(
                        WORK_CLOSED,
                        assertThrows(
                                        CredentialConfigurationException.class,
                                        () -> work.encode(INPUT))
                                .code());
                assertEquals(1, backend.active.get());
                assertEquals(1, backend.calls.get());
                backend.release.countDown();
                assertEquals("TEST-ONLY-ENCODING", running.get(5, TimeUnit.SECONDS));
                work.close();
            } finally {
                backend.release.countDown();
            }
        }
    }

    @Test
    void errorsAreSanitizedAndWorkerThreadsDoNotInheritRequestContext() {
        String seeded = "seeded-sensitive-request-context";
        var inherited = new InheritableThreadLocal<String>();
        inherited.set(seeded);
        var observed = new AtomicReference<String>("not-run");
        var backend =
                new BoundedCredentialPasswords.Work() {
                    public String encode(String input) {
                        observed.set(inherited.get());
                        throw new IllegalStateException(seeded);
                    }

                    public boolean matches(String input, String stored) {
                        throw new AssertionError();
                    }

                    public void dummy(String input) {
                        throw new AssertionError();
                    }
                };
        try (var work = new BoundedCredentialPasswords(backend, limits(1, 1, 1000))) {
            var failure =
                    assertThrows(CredentialConfigurationException.class, () -> work.encode(INPUT));
            assertEquals(WORK_FAILED, failure.code());
            assertNull(failure.getCause());
            assertFalse(failure.toString().contains(seeded));
            assertNull(observed.get());
            assertFalse(work.toString().contains(seeded));
        } finally {
            inherited.remove();
        }
    }

    @Test
    void transactionResourcesAndNestedSubmissionAreRejectedBeforeWork() {
        var reference = new AtomicReference<BoundedCredentialPasswords>();
        var backend =
                new BoundedCredentialPasswords.Work() {
                    public String encode(String input) {
                        return reference.get().encode(input);
                    }

                    public boolean matches(String input, String stored) {
                        throw new AssertionError();
                    }

                    public void dummy(String input) {
                        throw new AssertionError();
                    }
                };
        try (var work = new BoundedCredentialPasswords(backend, limits(1, 1, 1000))) {
            reference.set(work);
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                assertEquals(
                        WORK_CONTEXT_INVALID,
                        assertThrows(
                                        CredentialConfigurationException.class,
                                        () -> work.encode(INPUT))
                                .code());
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }
            TransactionSynchronizationManager.initSynchronization();
            try {
                assertEquals(
                        WORK_CONTEXT_INVALID,
                        assertThrows(
                                        CredentialConfigurationException.class,
                                        () -> work.encode(INPUT))
                                .code());
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
            var resource = new Object();
            TransactionSynchronizationManager.bindResource(resource, new Object());
            try {
                assertEquals(
                        WORK_CONTEXT_INVALID,
                        assertThrows(
                                        CredentialConfigurationException.class,
                                        () -> work.encode(INPUT))
                                .code());
            } finally {
                TransactionSynchronizationManager.unbindResource(resource);
            }
            assertEquals(
                    WORK_CONTEXT_INVALID,
                    assertThrows(CredentialConfigurationException.class, () -> work.encode(INPUT))
                            .code());
        }
    }

    @Test
    void realSpringEncodingMatchingAndDummyWorkPreserveExistingPolicy() {
        var passwords = new CredentialPasswords(OfflinePasswordBlocklist.bundled());
        try (var work = new BoundedCredentialPasswords(passwords)) {
            org.springframework.security.crypto.password.PasswordEncoder encoder = work;
            String encoded = encoder.encode(INPUT);
            assertTrue(CredentialPasswords.supportsEncoding(encoded));
            assertTrue(encoder.matches(INPUT, encoded));
            assertFalse(encoder.upgradeEncoding(encoded)); // no automatic rehash policy is claimed
            assertFalse(work.matches("another wrong credential phrase", encoded));
            assertFalse(work.matches("x".repeat(10000), encoded));
            work.performDummyVerification(INPUT);
            assertThrows(CredentialInputException.class, () -> work.encode("short"));
            assertEquals(
                    STORED_CREDENTIAL_INVALID,
                    assertThrows(
                                    CredentialConfigurationException.class,
                                    () -> work.matches(INPUT, "{noop}seeded-secret"))
                            .code());
        }
    }

    @Test
    void workerRejectsAnExpiredQueuedJobBeforeTheCallerWaitExpires() throws Exception {
        var backend = new BlockingWork(1);
        var now = new AtomicLong();
        try (var work = new BoundedCredentialPasswords(backend, limits(1, 1, 2000), now::get);
                var callers = Executors.newFixedThreadPool(2)) {
            try {
                var running = callers.submit(() -> work.encode(INPUT));
                assertTrue(backend.started.await(5, TimeUnit.SECONDS));
                var expired = callers.submit(() -> work.encode(INPUT));
                await(() -> work.queuedWork() == 1);
                now.set(TimeUnit.SECONDS.toNanos(2));
                backend.release.countDown();
                assertEquals("TEST-ONLY-ENCODING", running.get(5, TimeUnit.SECONDS));
                assertEquals(WORK_CAPACITY, code(expired));
                assertEquals(1, backend.calls.get());
                assertEquals(0, work.queuedWork());
            } finally {
                backend.release.countDown();
            }
        }
    }
}
