// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import org.jspecify.annotations.NullMarked;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.vernac.runtime.*;
import org.vernac.saas.identity.domain.*;
import org.vernac.saas.identity.persistence.adapter.outbound.jdbc.*;
import org.vernac.saas.identity.persistence.domain.*;

import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/**
 * Private transactional persistence, not a PasswordEncoder, user lookup endpoint or login provider.
 */
@NullMarked
final class JdbcIdentityCredentials {
    enum Code {
        INVALID_ENCODING,
        STORAGE_UNAVAILABLE
    }

    static final class Failure extends IllegalStateException {
        private final Code code;

        Failure(Code code) {
            super("Identity credentials unavailable: " + code);
            this.code = code;
        }

        Code code() {
            return code;
        }
    }

    /** Private handoff for expensive verification outside transactions. Not a stable public DTO. */
    static final class Snapshot {
        private final UserId subject;
        private final long credentialVersion, securityEpoch;
        private final IdentityPasswordEncoding encoding;

        private Snapshot(Identity identity, IdentityCredential credential) {
            subject = identity.id();
            credentialVersion = credential.persistenceState().version();
            securityEpoch = identity.securityEpoch().number();
            encoding = credential.encoding();
        }

        UserId subject() {
            return subject;
        }

        long credentialVersion() {
            return credentialVersion;
        }

        long securityEpoch() {
            return securityEpoch;
        }

        String encoded() {
            return encoding.encoded();
        }

        @Override
        public String toString() {
            return "IdentityCredentialSnapshot[REDACTED]";
        }
    }

    private record State(Identity identity, IdentityCredential credential) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcIdentityCapacity capacity;
    private final IdentityRepository identities;
    private final IdentityCredentialRepository credentials;

    JdbcIdentityCredentials(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = DomainChecks.requireNonNull(jdbc, "Identity credential JDBC template");
        capacity = new JdbcIdentityCapacity(jdbc);
        identities = new JdbcIdentityRepository(jdbc);
        credentials = new JdbcIdentityCredentialRepository(jdbc);
    }

    /**
     * Only a pending identity without a credential. Verification/activation/provisioning must
     * compose here.
     */
    boolean enroll(UserId subject, String encoded) {
        DomainChecks.requireNonNull(subject, "Identity credential subject");
        var encoding = encoding(encoded);
        return mutate(
                () -> {
                    var identity = lockedIdentity(subject);
                    if (identity.isEmpty()
                            || identity.get().status() != IdentityStatus.PENDING_VERIFICATION
                            || credentials.existing(subject).isPresent()) return false;
                    Instant now = Instant.now();
                    credentials.save(
                            IdentityCredential.reconstitute(subject, encoding, now, now, 0));
                    return true;
                });
    }

    Optional<Snapshot> snapshot(UserId subject) {
        DomainChecks.requireNonNull(subject, "Identity credential subject");
        return mutate(
                () -> {
                    var identity = lockedIdentity(subject);
                    if (identity.isEmpty() || identity.get().status() != IdentityStatus.ACTIVE)
                        return Optional.empty();
                    return credentials
                            .existing(subject)
                            .map(credential -> new Snapshot(identity.get(), credential));
                });
    }

    /**
     * Recheck after expensive Spring verification; not an authentication/session grant on its own.
     */
    boolean confirm(Snapshot expected) {
        DomainChecks.requireNonNull(expected, "Identity credential snapshot");
        return mutate(() -> matching(expected).isPresent());
    }

    /** A real password change/reset. The trusted caller must have authorized the operation. */
    boolean replace(Snapshot expected, String encoded) {
        return update(expected, encoded, true);
    }

    /** Caller must have verified equivalence to the same password using the approved encoder. */
    boolean rehash(Snapshot expected, String encoded) {
        return update(expected, encoded, false);
    }

    private boolean update(Snapshot expected, String encoded, boolean advanceEpoch) {
        DomainChecks.requireNonNull(expected, "Identity credential snapshot");
        var encoding = encoding(encoded);
        return mutate(
                () -> {
                    var current = matching(expected);
                    if (current.isEmpty()) return false;
                    var identity = current.get().identity();
                    var credential = current.get().credential();
                    credential.replaceEncoding(encoding);
                    if (advanceEpoch) {
                        identity.credentialChanged();
                        identities.save(identity);
                    }
                    credentials.save(credential);
                    return true;
                });
    }

    private Optional<State> matching(Snapshot expected) {
        var identity = lockedIdentity(expected.subject());
        if (identity.isEmpty()
                || identity.get().status() != IdentityStatus.ACTIVE
                || identity.get().securityEpoch().number() != expected.securityEpoch())
            return Optional.empty();
        var credential = credentials.existing(expected.subject());
        return credential.isPresent()
                        && credential.get().persistenceState().version()
                                == expected.credentialVersion()
                ? Optional.of(new State(identity.get(), credential.get()))
                : Optional.empty();
    }

    private Optional<Identity> lockedIdentity(UserId subject) {
        var ids =
                jdbc.query(
                        "SELECT \"id\" FROM \"org.vernac.saas.identity.persistence\".\"Identity\""
                                + " WHERE \"id\"=:id FOR UPDATE",
                        Map.of("id", subject.value()),
                        (rows, n) -> rows.getObject(1, UUID.class));
        return ids.isEmpty() ? Optional.empty() : identities.existing(subject);
    }

    private static IdentityPasswordEncoding encoding(String value) {
        try {
            return IdentityPasswordEncoding.of(value);
        } catch (DomainValidationException invalid) {
            throw new Failure(Code.INVALID_ENCODING);
        }
    }

    private <T> T mutate(Supplier<T> work) {
        return capacity.withBudgetLock(
                () -> {
                    try {
                        return work.get();
                    } catch (RuntimeException | Error failure) {
                        ((ConnectionHolder)
                                        TransactionSynchronizationManager.getResource(
                                                jdbc.getJdbcTemplate().getDataSource()))
                                .setRollbackOnly();
                        if (failure instanceof DataAccessException
                                || failure instanceof DomainValidationException)
                            throw new Failure(Code.STORAGE_UNAVAILABLE);
                        throw failure;
                    }
                });
    }
}
