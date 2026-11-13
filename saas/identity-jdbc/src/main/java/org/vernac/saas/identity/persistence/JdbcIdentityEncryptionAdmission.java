// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.vernac.saas.identity.persistence.adapter.outbound.jdbc.*;
import org.vernac.saas.identity.persistence.domain.*;

import java.time.*;
import java.util.*;
import java.util.function.Supplier;

import javax.sql.DataSource;

/** Independently committed, bounded key identity and encryption-attempt registry. */
final class JdbcIdentityEncryptionAdmission implements IdentityDeliveryCrypto.EncryptionAdmission {
    static final IdentityEncryptionRegistryId ID =
            IdentityEncryptionRegistryId.of(
                    UUID.fromString("4b455952-6567-4973-8000-000000000001"));
    private final NamedParameterJdbcTemplate jdbc;
    private final DataSource source;
    private final IdentityEncryptionKeyRepository keys;
    private final IdentityEncryptionRegistryRepository registry;
    private final TransactionTemplate transaction;
    private final Clock clock;

    JdbcIdentityEncryptionAdmission(
            NamedParameterJdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.source = Objects.requireNonNull(jdbc.getJdbcTemplate().getDataSource());
        this.keys = new JdbcIdentityEncryptionKeyRepository(jdbc);
        this.registry = new JdbcIdentityEncryptionRegistryRepository(jdbc);
        this.clock = Objects.requireNonNull(clock);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(5);
    }

    void initialize() {
        independent(
                () -> {
                    var existing = registry.existing(ID);
                    long count = retainedKeys();
                    if (existing.isPresent()) {
                        if (existing.get().used().amount() != count) throw unavailable();
                    } else {
                        if (count != 0) throw unavailable();
                        Instant now = clock.instant();
                        registry.save(
                                IdentityEncryptionRegistry.reconstitute(
                                        ID, IdentityEncryptionKeyCount.of(0), now, now, 0));
                    }
                    return true;
                });
    }

    @Override
    public void reserve(String name, String fingerprint) {
        independent(
                () -> {
                    var counter =
                            registry.existing(ID)
                                    .orElseThrow(JdbcIdentityEncryptionAdmission::unavailable);
                    if (counter.used().amount() != retainedKeys()) throw unavailable();
                    var keyName = IdentityEncryptionKeyName.of(name);
                    var material = IdentityEncryptionFingerprint.of(fingerprint);
                    var found = keys.named(keyName);
                    IdentityEncryptionKey key;
                    if (found.isPresent()) {
                        key = found.get();
                        if (!key.fingerprint().equals(material)) throw unavailable();
                    } else {
                        if (keys.matching(material).isPresent() || !counter.register())
                            throw unavailable();
                        key =
                                IdentityEncryptionKey.create(
                                        keyName, material, IdentityEncryptionAttempts.of(0));
                        registry.save(counter);
                    }
                    if (!key.reserve()) throw unavailable();
                    keys.save(key);
                    return true;
                });
    }

    private long retainedKeys() {
        Long count =
                jdbc.getJdbcTemplate()
                        .queryForObject(
                                "SELECT count(*) FROM (SELECT 1 FROM"
                                    + " \"org.vernac.saas.identity.persistence\".\"IdentityEncryptionKey\""
                                    + " LIMIT 65) bounded",
                                Long.class);
        if (count == null || count > 64) throw unavailable();
        return count;
    }

    private void boundWriter() {
        var holder = TransactionSynchronizationManager.getResource(source);
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !(holder instanceof ConnectionHolder)
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw unavailable();
        var connection = DataSourceUtils.getConnection(source);
        try {
            if (connection.getAutoCommit()
                    || !DataSourceUtils.isConnectionTransactional(connection, source))
                throw unavailable();
        } catch (java.sql.SQLException invalid) {
            throw unavailable();
        } finally {
            DataSourceUtils.releaseConnection(connection, source);
        }
    }

    private <T> T independent(Supplier<T> action) {
        // No suspension of an outer writer: permits are acquired before business locks.
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.getResourceMap().isEmpty())
            throw unavailable();
        try {
            return Objects.requireNonNull(
                    transaction.execute(
                            status -> {
                                boundWriter();
                                jdbc.getJdbcTemplate()
                                        .query(
                                                "SELECT pg_advisory_xact_lock(?, ?)",
                                                (ResultSetExtractor<Void>) rows -> null,
                                                0x564e4143,
                                                0x49444b59);
                                return action.get();
                            }));
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("IDENTITY_DELIVERY_UNAVAILABLE");
    }

    @Override
    public String toString() {
        return "JdbcIdentityEncryptionAdmission[REDACTED]";
    }
}
