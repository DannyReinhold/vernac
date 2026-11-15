// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import org.springframework.context.SmartLifecycle;

import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Explicitly constructed only by the future activated Identity/Auth assembly. */
final class IdentityMailPolling implements SmartLifecycle {
    private final JdbcIdentityMailWorker worker;
    private final Runnable admissionCleanup;
    private final AtomicLong nextWarning = new AtomicLong();
    private volatile boolean running;
    private ScheduledExecutorService executor;

    IdentityMailPolling(JdbcIdentityMailWorker worker, Runnable admissionCleanup) {
        this.worker = Objects.requireNonNull(worker);
        this.admissionCleanup = Objects.requireNonNull(admissionCleanup);
    }

    @Override
    public synchronized void start() {
        if (running) return;
        worker.initialize();
        executor =
                Executors.newScheduledThreadPool(
                        2,
                        task -> {
                            var thread = new Thread(null, task, "identity-mail", 0, false);
                            thread.setDaemon(true);
                            return thread;
                        });
        running = true;
        executor.scheduleWithFixedDelay(
                () -> safe(() -> worker.runOnce(50)), 0, 1, TimeUnit.SECONDS);
        executor.scheduleWithFixedDelay(
                () -> {
                    safe(() -> worker.sweep(500));
                    safe(() -> worker.expireChallenges(500));
                    safe(() -> worker.expireRegistrations(500));
                    safe(admissionCleanup);
                },
                0,
                1,
                TimeUnit.SECONDS);
    }

    private void safe(Runnable action) {
        if (!running) return;
        try {
            action.run();
        } catch (RuntimeException unavailable) {
            long now = System.nanoTime(), next = nextWarning.get();
            if ((next == 0 || now - next >= 0)
                    && nextWarning.compareAndSet(next, now + TimeUnit.MINUTES.toNanos(1)))
                System.getLogger(IdentityMailPolling.class.getName())
                        .log(
                                System.Logger.Level.ERROR,
                                "Identity background processing unavailable");
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (executor != null) executor.shutdown();
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        var current = executor;
        if (current == null || current.isTerminated()) {
            callback.run();
            return;
        }
        var waiter =
                new Thread(
                        null,
                        () -> {
                            try {
                                while (!current.awaitTermination(1, TimeUnit.SECONDS)) {}
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            } finally {
                                callback.run();
                            }
                        },
                        "identity-mail-shutdown",
                        0,
                        false);
        waiter.setDaemon(true);
        waiter.start();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
