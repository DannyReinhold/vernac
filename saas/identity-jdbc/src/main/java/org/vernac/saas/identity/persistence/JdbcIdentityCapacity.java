// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import org.jspecify.annotations.NullMarked;
import org.springframework.dao.*;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.vernac.runtime.DomainChecks;
import org.vernac.runtime.DomainValidationException;
import org.vernac.saas.identity.persistence.adapter.outbound.jdbc.JdbcIdentityCapacityRepository;
import org.vernac.saas.identity.persistence.domain.*;

import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

import javax.sql.DataSource;

/**
 * Private storage-budget primitive. No public endpoint, bean, migration runner or abuse counter.
 */
@NullMarked
final class JdbcIdentityCapacity {
    enum Resource {
        PENDING_REGISTRATIONS(1, 10_000),
        ACTIONABLE_CHALLENGES(2, 20_000),
        UNSENT_MAIL_JOBS(3, 10_000),
        UNSENT_MAIL_BYTES(4, 64 * 1_024 * 1_024),
        TERMINAL_METADATA(5, 20_000),
        LIMITER_KEYS(6, 50_000);
        private final int code;
        private final long initialLimit;

        Resource(int code, long initialLimit) {
            this.code = code;
            this.initialLimit = initialLimit;
        }

        IdentityCapacityId id() {
            // Stable technical row identities, not user IDs or security capabilities.
            return IdentityCapacityId.of(new UUID(0x8b0f5c39b70d4bc5L, 0x8bac000000000000L | code));
        }
    }

    enum Code {
        NOT_INITIALIZED,
        CONFIGURATION_MISMATCH,
        ACCOUNTING_MISMATCH,
        STORAGE_UNAVAILABLE
    }

    static final class Failure extends IllegalStateException {
        private final Code code;

        Failure(Code code) {
            super("Identity capacity unavailable: " + code);
            this.code = code;
        }

        Code code() {
            return code;
        }
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final DataSource source;
    private final IdentityCapacityRepository repository;

    JdbcIdentityCapacity(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = DomainChecks.requireNonNull(jdbc, "Identity capacity JDBC template");
        source =
                DomainChecks.requireNonNull(
                        jdbc.getJdbcTemplate().getDataSource(), "Identity capacity DataSource");
        repository = new JdbcIdentityCapacityRepository(jdbc);
    }

    static Map<Resource, Long> defaults() {
        var result = new EnumMap<Resource, Long>(Resource.class);
        for (var resource : Resource.values()) result.put(resource, resource.initialLimit);
        return Collections.unmodifiableMap(result);
    }

    /**
     * Bootstrap under a real writer transaction. Drift fails; it does not silently replace limits.
     */
    void initialize(Map<Resource, Long> limits) {
        var desired = amounts(limits);
        if (desired.size() != Resource.values().length)
            throw new Failure(Code.CONFIGURATION_MISMATCH);
        locked(() -> {
            var existing = read(desired.keySet(), false);
            for (var resource : desired.keySet()) {
                var row = existing.get(resource);
                if (row != null && row.limit().amount() != desired.get(resource)) {
                    throw new Failure(Code.CONFIGURATION_MISMATCH);
                }
            }
            Instant now = Instant.now();
            for (var resource : desired.keySet()) {
                if (!existing.containsKey(resource))
                    repository.save(
                            IdentityCapacity.reconstitute(
                                    resource.id(),
                                    IdentityCapacityQuantity.of(0),
                                    IdentityCapacityQuantity.of(desired.get(resource)),
                                    now,
                                    now,
                                    0));
            }
            return null;
        });
    }

    /** Check the complete bundle before writing any counter. Denial does not partially allocate. */
    boolean reserve(Map<Resource, Long> requested) {
        var wanted = amounts(requested);
        return locked(() -> {
            var rows = read(wanted.keySet(), true);
            for (var resource : wanted.keySet()) {
                var row = rows.get(resource);
                long used = row.used().amount(), limit = row.limit().amount();
                if (used > limit || wanted.get(resource) > limit - used) return false;
            }
            for (var resource : wanted.keySet()) {
                var row = rows.get(resource);
                if (!row.reserve(wanted.get(resource))) throw new Failure(Code.ACCOUNTING_MISMATCH);
                repository.save(row);
            }
            return true;
        });
    }

    /**
     * Release only after the caller wins the guarded record transition in this same transaction.
     * Counters alone cannot identify duplicate completion; elapsed time never releases storage.
     */
    void release(Map<Resource, Long> released) {
        var quantities = amounts(released);
        locked(() -> {
            var rows = read(quantities.keySet(), true);
            for (var resource : quantities.keySet()) {
                if (rows.get(resource).used().amount() < quantities.get(resource)) {
                    throw new Failure(Code.ACCOUNTING_MISMATCH);
                }
            }
            for (var resource : quantities.keySet()) {
                var row = rows.get(resource);
                row.release(quantities.get(resource));
                repository.save(row);
            }
            return null;
        });
    }

    /**
     * Controlled operational change; lowering below occupancy never deletes work or resets usage.
     */
    void changeLimit(Resource resource, long limit) {
        DomainChecks.requireNonNull(resource, "Identity capacity resource");
        if (limit <= 0)
            throw new DomainValidationException("Identity capacity limit must be positive");
        locked(() -> {
            var row = read(EnumSet.of(resource), true).get(resource);
            if (row.limit().amount() != limit) {
                row.changeLimit(limit);
                repository.save(row);
            }
            return null;
        });
    }

    /** Diagnostic snapshot only; never use read-then-write in place of reserve. */
    long usage(Resource resource) {
        DomainChecks.requireNonNull(resource, "Identity capacity resource");
        return safely(false, () -> read(EnumSet.of(resource), true).get(resource).used().amount());
    }

    private EnumMap<Resource, Long> amounts(Map<Resource, Long> values) {
        DomainChecks.requireNonNull(values, "Identity capacity quantities");
        if (values.isEmpty())
            throw new DomainValidationException("Identity capacity quantities must not be empty");
        var result = new EnumMap<Resource, Long>(Resource.class);
        for (var entry : values.entrySet()) {
            var key = DomainChecks.requireNonNull(entry.getKey(), "Identity capacity resource");
            long quantity =
                    DomainChecks.requireNonNull(entry.getValue(), "Identity capacity quantity");
            if (quantity <= 0)
                throw new DomainValidationException("Identity capacity quantity must be positive");
            result.put(key, quantity);
        }
        return result;
    }

    private EnumMap<Resource, IdentityCapacity> read(Set<Resource> resources, boolean required) {
        var result = new EnumMap<Resource, IdentityCapacity>(Resource.class);
        for (var resource : resources) {
            var row = repository.existing(resource.id());
            if (required && row.isEmpty()) throw new Failure(Code.NOT_INITIALIZED);
            row.ifPresent(value -> result.put(resource, value));
        }
        return result;
    }

    /**
     * Acquire the budget lock before guarded record transitions; callback failures retain their
     * type.
     */
    <T> T withBudgetLock(Supplier<T> work) {
        DomainChecks.requireNonNull(work, "Identity capacity work");
        safely(
                true,
                () -> {
                    acquire();
                    return null;
                });
        return work.get();
    }

    private <T> T locked(Supplier<T> work) {
        return safely(
                true,
                () -> {
                    acquire();
                    return work.get();
                });
    }

    private void acquire() {
        // One fixed transaction-scoped lock avoids per-resource lock-order cycles.
        jdbc.getJdbcTemplate()
                .query(
                        "SELECT pg_advisory_xact_lock(?, ?)",
                        (ResultSetExtractor<Void>) rows -> null,
                        0x564e4143,
                        0x49444350);
    }

    private <T> T safely(boolean write, Supplier<T> work) {
        try {
            requireBoundWriter(write);
            return work.get();
        } catch (OptimisticLockingFailureException changed) {
            throw new OptimisticLockingFailureException("Identity capacity changed concurrently");
        } catch (SQLException | DataAccessException | DomainValidationException unavailable) {
            throw new Failure(Code.STORAGE_UNAVAILABLE);
        }
    }

    private void requireBoundWriter(boolean write) throws SQLException {
        var holder = TransactionSynchronizationManager.getResource(source);
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || !(holder instanceof ConnectionHolder)
                || (write && TransactionSynchronizationManager.isCurrentTransactionReadOnly())) {
            throw new IllegalTransactionStateException(
                    "Identity capacity requires its bound writer transaction");
        }
        for (var value : TransactionSynchronizationManager.getResourceMap().values()) {
            if (value instanceof ConnectionHolder && value != holder) {
                throw new IllegalTransactionStateException(
                        "Identity capacity requires a single JDBC writer boundary");
            }
        }
        var connection = DataSourceUtils.getConnection(source);
        try {
            if (connection.getAutoCommit()
                    || !DataSourceUtils.isConnectionTransactional(connection, source)) {
                throw new IllegalTransactionStateException(
                        "Identity capacity requires its bound writer transaction");
            }
        } finally {
            DataSourceUtils.releaseConnection(connection, source);
        }
    }
}
