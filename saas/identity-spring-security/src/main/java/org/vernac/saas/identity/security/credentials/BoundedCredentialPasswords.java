// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security.credentials;

import static org.vernac.saas.identity.security.credentials.CredentialConfigurationException.Code.*;

import org.jspecify.annotations.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.vernac.runtime.DomainChecks;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.LongSupplier;

/**
 * Explicit application-scoped owner of bounded Spring password work. No automatic beans or filters.
 */
@NullMarked
public final class BoundedCredentialPasswords implements PasswordEncoder, AutoCloseable {
    public record Limits(int parallelism, int queueCapacity, Duration maximumAdmissionWait) {
        public Limits {
            Objects.requireNonNull(maximumAdmissionWait, "Credential admission wait");
            long nanos;
            try {
                nanos = maximumAdmissionWait.toNanos();
            } catch (ArithmeticException invalid) {
                throw new IllegalArgumentException("Invalid credential admission wait");
            }
            if (parallelism < 1
                    || parallelism > 64
                    || queueCapacity < 0
                    || queueCapacity > 1024
                    || nanos < 1
                    || nanos > TimeUnit.SECONDS.toNanos(5))
                throw new IllegalArgumentException("Invalid credential work limits");
        }

        public static Limits defaults() {
            return new Limits(4, 16, Duration.ofMillis(250));
        }
    }

    // Package-private deterministic test seam, not a public encoder extension/fallback API.
    interface Work {
        String encode(String input);

        boolean matches(String input, String stored);

        void dummy(String input);
    }

    private static final ThreadLocal<Boolean> IN_WORKER = new ThreadLocal<>();
    private final Work work;
    private final Limits limits;
    private final LongSupplier nanoTime;
    private final ThreadPoolExecutor executor;

    public BoundedCredentialPasswords(CredentialPasswords passwords) {
        this(passwords, Limits.defaults());
    }

    public BoundedCredentialPasswords(CredentialPasswords passwords, Limits limits) {
        this(delegate(passwords), limits);
    }

    BoundedCredentialPasswords(Work work, Limits limits) {
        this(work, limits, System::nanoTime);
    }

    BoundedCredentialPasswords(Work work, Limits limits, LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "Credential monotonic clock");
        this.work = Objects.requireNonNull(work, "Credential work");
        this.limits = Objects.requireNonNull(limits, "Credential work limits");
        var sequence = new AtomicInteger();
        BlockingQueue<Runnable> queue =
                limits.queueCapacity() == 0
                        ? new SynchronousQueue<>()
                        : new ArrayBlockingQueue<>(limits.queueCapacity());
        executor =
                new ThreadPoolExecutor(
                        limits.parallelism(),
                        limits.parallelism(),
                        0,
                        TimeUnit.MILLISECONDS,
                        queue,
                        task ->
                                Thread.ofPlatform()
                                        .daemon(true)
                                        .inheritInheritableThreadLocals(false)
                                        .name(
                                                "vernac-credential-work-"
                                                        + sequence.incrementAndGet())
                                        .uncaughtExceptionHandler(
                                                (thread, error) ->
                                                        System.getLogger(
                                                                        BoundedCredentialPasswords
                                                                                .class
                                                                                .getName())
                                                                .log(
                                                                        System.Logger.Level.ERROR,
                                                                        "Credential worker"
                                                                                + " terminated"))
                                        .unstarted(task),
                        new ThreadPoolExecutor.AbortPolicy());
    }

    private static Work delegate(CredentialPasswords passwords) {
        Objects.requireNonNull(passwords, "Credential passwords");
        return new Work() {
            public String encode(String input) {
                return passwords.encode(input);
            }

            public boolean matches(String input, String stored) {
                return passwords.matches(input, stored);
            }

            public void dummy(String input) {
                passwords.performDummyVerification(input);
            }
        };
    }

    @Override
    public String encode(@Nullable CharSequence password) {
        boundary();
        String normalized = PasswordInput.forCreation(password);
        return execute(() -> Objects.requireNonNull(work.encode(normalized)));
    }

    @Override
    public boolean matches(@Nullable CharSequence password, String stored) {
        boundary();
        DomainChecks.requireNonNull(password, "Credential password");
        if (!CredentialPasswords.supportsEncoding(stored))
            throw new CredentialConfigurationException(STORED_CREDENTIAL_INVALID);
        String normalized;
        try {
            normalized = PasswordInput.normalize(password);
        } catch (CredentialInputException invalid) {
            return false;
        }
        return execute(() -> work.matches(normalized, stored));
    }

    public void performDummyVerification(@Nullable CharSequence password) {
        boundary();
        DomainChecks.requireNonNull(password, "Credential password");
        String normalized;
        try {
            normalized = PasswordInput.normalize(password);
        } catch (CredentialInputException invalid) {
            return;
        }
        execute(
                () -> {
                    work.dummy(normalized);
                    return null;
                });
    }

    private static void boundary() {
        if (Boolean.TRUE.equals(IN_WORKER.get())
                || TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.getResourceMap().isEmpty())
            throw new CredentialConfigurationException(WORK_CONTEXT_INVALID);
    }

    private <T> T execute(Callable<T> operation) {
        var job =
                new Job<>(
                        operation,
                        executor,
                        nanoTime.getAsLong() + limits.maximumAdmissionWait().toNanos(),
                        nanoTime);
        try {
            executor.execute(job);
        } catch (RejectedExecutionException rejected) {
            var code = executor.isShutdown() ? WORK_CLOSED : WORK_CAPACITY;
            job.cancelQueued(code);
            throw new CredentialConfigurationException(code);
        } catch (RuntimeException unavailable) {
            job.cancelQueued(WORK_FAILED);
            throw new CredentialConfigurationException(WORK_FAILED);
        } catch (Error fatal) {
            job.cancelQueued(WORK_FAILED);
            throw fatal;
        }
        try {
            if (!job.started.await(limits.maximumAdmissionWait().toNanos(), TimeUnit.NANOSECONDS))
                job.cancelQueued(WORK_CAPACITY);
            return job.result.get();
        } catch (InterruptedException interrupted) {
            job.cancelQueued(WORK_INTERRUPTED);
            Thread.currentThread().interrupt();
            throw new CredentialConfigurationException(WORK_INTERRUPTED);
        } catch (ExecutionException failed) {
            var cause = failed.getCause();
            if (cause instanceof CredentialInputException input) throw input;
            if (cause instanceof CredentialConfigurationException configuration)
                throw configuration;
            throw new CredentialConfigurationException(WORK_FAILED);
        }
    }

    private static final class Job<T> implements Runnable {
        private static final int QUEUED = 0, RUNNING = 1, DONE = 2, CANCELLED = 3;
        private final AtomicInteger state = new AtomicInteger(QUEUED);
        private final AtomicReference<@Nullable Callable<T>> operation;
        private final ThreadPoolExecutor executor;
        private final long deadline;
        private final LongSupplier nanoTime;
        private final CountDownLatch started = new CountDownLatch(1);
        private final CompletableFuture<T> result = new CompletableFuture<>();

        Job(
                Callable<T> operation,
                ThreadPoolExecutor executor,
                long deadline,
                LongSupplier nanoTime) {
            this.nanoTime = nanoTime;
            this.operation = new AtomicReference<>(operation);
            this.executor = executor;
            this.deadline = deadline;
        }

        boolean cancelQueued(CredentialConfigurationException.Code code) {
            if (!state.compareAndSet(QUEUED, CANCELLED)) return false;
            operation.set(null);
            result.completeExceptionally(new CredentialConfigurationException(code));
            started.countDown();
            executor.remove(this);
            return true;
        }

        @Override
        public void run() {
            if (executor.isShutdown()) {
                cancelQueued(WORK_CLOSED);
                return;
            }
            if (nanoTime.getAsLong() - deadline >= 0) {
                cancelQueued(WORK_CAPACITY);
                return;
            }
            if (!state.compareAndSet(QUEUED, RUNNING)) return;
            started.countDown();
            IN_WORKER.set(true);
            try {
                result.complete(Objects.requireNonNull(operation.getAndSet(null)).call());
            } catch (CredentialInputException | CredentialConfigurationException safe) {
                result.completeExceptionally(safe);
            } catch (Exception failure) {
                result.completeExceptionally(new CredentialConfigurationException(WORK_FAILED));
            } catch (Error fatal) {
                result.completeExceptionally(new CredentialConfigurationException(WORK_FAILED));
                throw fatal;
            } finally {
                operation.set(null);
                IN_WORKER.remove();
                state.set(DONE);
            }
        }

        @Override
        public String toString() {
            return "CredentialWork[REDACTED]";
        }
    }

    int queuedWork() {
        return executor.getQueue().size();
    }

    @Override
    public void close() {
        for (var task : executor.shutdownNow()) ((Job<?>) task).cancelQueued(WORK_CLOSED);
    }

    @Override
    public String toString() {
        return "BoundedCredentialPasswords[bounded]";
    }
}
