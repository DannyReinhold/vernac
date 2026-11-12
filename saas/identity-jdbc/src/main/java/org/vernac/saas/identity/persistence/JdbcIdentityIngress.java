// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import static org.vernac.saas.identity.persistence.JdbcIdentityCapacity.Resource.LIMITER_KEYS;

import org.jspecify.annotations.NullMarked;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.vernac.runtime.DomainChecks;
import org.vernac.runtime.DomainValidationException;
import org.vernac.saas.identity.persistence.adapter.outbound.jdbc.JdbcIdentityIngressRepository;
import org.vernac.saas.identity.persistence.domain.*;

import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/**
 * Private coarse admission gate. Invoke before lookup, per-key allocation and business
 * transactions.
 */
@NullMarked
final class JdbcIdentityIngress {
    static final long DEFAULT_LIMIT = 300;
    static final IdentityIngressId ID =
            IdentityIngressId.of(UUID.fromString("696e6772-6573-4373-8000-000000000001"));

    enum Code {
        NOT_INITIALIZED,
        CONFIGURATION_MISMATCH,
        KEY_CAPACITY,
        STORAGE_UNAVAILABLE
    }

    static final class Failure extends IllegalStateException {
        private final Code code;

        Failure(Code code) {
            super("Identity ingress unavailable: " + code);
            this.code = code;
        }

        Code code() {
            return code;
        }
    }

    record Decision(boolean admitted, int retryAfterSeconds) {}

    private final JdbcIdentityCapacity capacity;
    private final IdentityIngressRepository repository;
    private final TransactionTemplate transaction;
    private final Clock clock;

    JdbcIdentityIngress(
            NamedParameterJdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
        DomainChecks.requireNonNull(jdbc, "Identity ingress JDBC template");
        this.clock = DomainChecks.requireNonNull(clock, "Identity ingress clock");
        capacity = new JdbcIdentityCapacity(jdbc);
        repository = new JdbcIdentityIngressRepository(jdbc);
        transaction =
                new TransactionTemplate(
                        DomainChecks.requireNonNull(
                                manager, "Identity ingress transaction manager"));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(5);
    }

    /**
     * Explicit startup; the private capacity ledger must already be initialized. Never reset usage.
     */
    void initialize(long limit) {
        positive(limit);
        independently(
                () -> {
                    var existing = repository.existing(ID);
                    if (existing.isPresent()) {
                        if (existing.get().limit().amount() != limit)
                            throw new Failure(Code.CONFIGURATION_MISMATCH);
                        return true;
                    }
                    if (!capacity.reserve(Map.of(LIMITER_KEYS, 1L)))
                        throw new Failure(Code.KEY_CAPACITY);
                    Instant now = clock.instant();
                    repository.save(
                            IdentityIngress.reconstitute(
                                    ID,
                                    IdentityIngressMinute.of(
                                            Math.floorDiv(now.getEpochSecond(), 60)),
                                    IdentityIngressCount.of(0),
                                    IdentityIngressCount.of(limit),
                                    now,
                                    now,
                                    0));
                    return true;
                });
    }

    Decision tryAcquire() {
        return independently(
                () -> {
                    var row =
                            repository
                                    .existing(ID)
                                    .orElseThrow(() -> new Failure(Code.NOT_INITIALIZED));
                    Instant now =
                            clock.instant(); // authoritative decision time after obtaining the
                    // database lock
                    boolean admitted = row.attempt(Math.floorDiv(now.getEpochSecond(), 60));
                    if (admitted) repository.save(row);
                    return new Decision(
                            admitted,
                            admitted ? 0 : 60 - (int) Math.floorMod(now.getEpochSecond(), 60));
                });
    }

    /** Operational limit changes preserve attempts, including occupancy above a lowered cap. */
    void changeLimit(long limit) {
        positive(limit);
        independently(
                () -> {
                    var row =
                            repository
                                    .existing(ID)
                                    .orElseThrow(() -> new Failure(Code.NOT_INITIALIZED));
                    if (row.limit().amount() != limit) {
                        row.changeLimit(limit);
                        repository.save(row);
                    }
                    return true;
                });
    }

    private static void positive(long value) {
        if (value <= 0)
            throw new IllegalArgumentException("Identity ingress limit must be positive");
    }

    private <T> T independently(Supplier<T> work) {
        // Admission must precede business work. Suspending a writer that holds the budget lock
        // would deadlock with its own admission transaction and could permit accidental refunds.
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.getResourceMap().isEmpty()) {
            throw new IllegalTransactionStateException(
                    "Identity ingress must precede business transactions");
        }
        try {
            return Objects.requireNonNull(
                    transaction.execute(status -> capacity.withBudgetLock(work)));
        } catch (DataAccessException
                | TransactionException
                | DomainValidationException unavailable) {
            throw new Failure(Code.STORAGE_UNAVAILABLE);
        }
    }
}
