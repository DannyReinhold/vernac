// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import static org.vernac.saas.identity.persistence.JdbcIdentityCapacity.Resource.*;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.vernac.runtime.*;
import org.vernac.saas.identity.domain.*;
import org.vernac.saas.identity.persistence.adapter.outbound.jdbc.*;
import org.vernac.saas.identity.persistence.domain.*;

import java.time.*;
import java.util.*;
import java.util.function.*;

/** Private pending-lifetime primitive. No address claim, mail, endpoint or authentication grant. */
@NullMarked
final class JdbcIdentityRegistrations {
    enum Code {
        CAPACITY,
        STORAGE_UNAVAILABLE,
        INCONSISTENT_STATE
    }

    static final class Failure extends IllegalStateException {
        private final Code code;

        Failure(Code code) {
            super("Identity registration unavailable: " + code);
            this.code = code;
        }

        Code code() {
            return code;
        }
    }

    /** Cursor is private operational state. A null cursor starts a new primary-key sweep. */
    record Sweep(int inspected, int removed, @Nullable UUID after) {}

    private static final String SCHEMA = "\"org.vernac.saas.identity.persistence\".";
    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcIdentityCapacity capacity;
    private final IdentityRepository identities;
    private final IdentityPendingRegistrationRepository registrations;
    private final IdentityChallengeRepository challenges;
    private final IdentityCredentialRepository credentials;
    private final Clock clock;

    JdbcIdentityRegistrations(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = DomainChecks.requireNonNull(jdbc, "Identity registration JDBC template");
        this.clock = DomainChecks.requireNonNull(clock, "Identity registration clock");
        capacity = new JdbcIdentityCapacity(jdbc);
        identities = new JdbcIdentityRepository(jdbc);
        registrations = new JdbcIdentityPendingRegistrationRepository(jdbc);
        challenges = new JdbcIdentityChallengeRepository(jdbc);
        credentials = new JdbcIdentityCredentialRepository(jdbc);
    }

    /** Trusted allocation only; a future address owner must compose this with its unique claim. */
    UserId create() {
        return mutate(
                () -> {
                    if (!capacity.reserve(Map.of(PENDING_REGISTRATIONS, 1L)))
                        throw new Failure(Code.CAPACITY);
                    Instant now = clock.instant();
                    var identity =
                            Identity.reconstitute(
                                    UserId.create(),
                                    IdentityStatus.PENDING_VERIFICATION,
                                    IdentitySecurityEpoch.of(0),
                                    now,
                                    now,
                                    0);
                    identities.save(identity);
                    registrations.save(
                            IdentityPendingRegistration.reconstitute(
                                    identity.id(),
                                    IdentityRegistrationDeadline.of(now.plus(Duration.ofDays(7))),
                                    now,
                                    now,
                                    0));
                    return identity.id();
                });
    }

    /** Eligibility only. Calling this does not renew the immutable first-creation deadline. */
    boolean pending(UserId subject) {
        DomainChecks.requireNonNull(subject, "Identity registration subject");
        return mutate(() -> pendingAt(subject, clock.instant()).isPresent());
    }

    /**
     * Only inside a trusted challenge-consumption action, after credential validation/encoding. The
     * local provisioning callback must join this writer; remote work is forbidden.
     */
    boolean complete(UserId subject, String encoded, Consumer<UserId> provision) {
        DomainChecks.requireNonNull(subject, "Identity registration subject");
        DomainChecks.requireNonNull(provision, "Identity registration provisioning action");
        return mutate(
                () -> {
                    var found = pendingAt(subject, clock.instant());
                    if (found.isEmpty()) return false;
                    var credential = new JdbcIdentityCredentials(jdbc);
                    if (!credential.enroll(subject, encoded))
                        throw new Failure(Code.INCONSISTENT_STATE);
                    var identity = identities.byId(subject);
                    identity.activate();
                    identities.save(identity);
                    registrations.delete(found.get());
                    capacity.release(Map.of(PENDING_REGISTRATIONS, 1L));
                    provision.accept(subject);
                    return true;
                });
    }

    /** Bounded indexed seek; deletion and all dependent refunds share the activation lock. */
    Sweep sweep(@Nullable UUID after, int batchSize) {
        if (batchSize < 1 || batchSize > 500)
            throw new IllegalArgumentException("Invalid identity registration cleanup batch");
        return mutate(
                () -> {
                    Instant now = clock.instant();
                    var parameters = new HashMap<String, Object>();
                    parameters.put("limit", batchSize);
                    if (after != null) parameters.put("after", after);
                    var ids =
                            jdbc.query(
                                    "SELECT \"id\" FROM "
                                            + SCHEMA
                                            + "\"IdentityPendingRegistration\""
                                            + (after == null ? "" : " WHERE \"id\">:after")
                                            + " ORDER BY \"id\" LIMIT :limit",
                                    parameters,
                                    (rs, n) -> UserId.of(rs.getObject(1, UUID.class)));
                    int removed = 0;
                    for (var subject : ids) {
                        var row = registrations.byId(subject);
                        if (now.isBefore(row.deadline().instant())) continue;
                        var identity =
                                lockedIdentity(subject)
                                        .orElseThrow(() -> new Failure(Code.INCONSISTENT_STATE));
                        // Never reinterpret active, disabled or closed state as abandoned
                        // registration.
                        if (identity.status() != IdentityStatus.PENDING_VERIFICATION)
                            throw new Failure(Code.INCONSISTENT_STATE);
                        // Delivery envelopes/address claims must be composed before a public flow
                        // ships.
                        for (var purpose : IdentityChallengePurpose.values()) {
                            var challenge = challenges.current(subject, purpose);
                            challenge.ifPresent(
                                    value -> {
                                        challenges.delete(value);
                                        capacity.release(Map.of(ACTIONABLE_CHALLENGES, 1L));
                                    });
                        }
                        credentials.existing(subject).ifPresent(credentials::delete);
                        registrations.delete(row);
                        identities.delete(identity);
                        capacity.release(Map.of(PENDING_REGISTRATIONS, 1L));
                        removed++;
                    }
                    return new Sweep(
                            ids.size(),
                            removed,
                            ids.size() < batchSize ? null : ids.getLast().value());
                });
    }

    private Optional<IdentityPendingRegistration> pendingAt(UserId subject, Instant now) {
        var identity = lockedIdentity(subject);
        var pending = registrations.existing(subject);
        if (pending.isEmpty()) return Optional.empty();
        if (identity.isEmpty() || identity.get().status() != IdentityStatus.PENDING_VERIFICATION)
            throw new Failure(Code.INCONSISTENT_STATE);
        return now.isBefore(pending.get().deadline().instant()) ? pending : Optional.empty();
    }

    private Optional<Identity> lockedIdentity(UserId subject) {
        var ids =
                jdbc.query(
                        "SELECT \"id\" FROM " + SCHEMA + "\"Identity\" WHERE \"id\"=:id FOR UPDATE",
                        Map.of("id", subject.value()),
                        (rs, n) -> rs.getObject(1, UUID.class));
        return ids.isEmpty() ? Optional.empty() : identities.existing(subject);
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
