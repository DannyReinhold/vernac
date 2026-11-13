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
import org.vernac.runtime.persistence.UniqueConstraintViolationException;
import org.vernac.saas.identity.domain.*;
import org.vernac.saas.identity.persistence.adapter.outbound.jdbc.*;
import org.vernac.saas.identity.persistence.domain.*;
import org.vernac.saas.identity.security.credentials.CredentialInputException;
import org.vernac.saas.identity.security.credentials.LoginAddress;

import java.time.*;
import java.util.*;
import java.util.function.*;

/**
 * Private address ownership and charged pending lifetime; no public ingress, mail or
 * authentication.
 */
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

    enum Outcome {
        CREATED,
        PENDING,
        UNAVAILABLE
    }

    /** Trusted handoff only. Public responses must not reveal the outcome or ownership facts. */
    static final class Request {
        private final Outcome outcome;
        private final @Nullable UserId subject;
        private final @Nullable String deliveryAddress;

        private Request(
                Outcome outcome, @Nullable UserId subject, @Nullable String deliveryAddress) {
            this.outcome = outcome;
            this.subject = subject;
            this.deliveryAddress = deliveryAddress;
        }

        Outcome outcome() {
            return outcome;
        }

        UserId subject() {
            if (subject == null)
                throw new IllegalStateException("Identity registration action unavailable");
            return subject;
        }

        String deliveryAddress() {
            if (deliveryAddress == null)
                throw new IllegalStateException("Identity registration action unavailable");
            return deliveryAddress;
        }

        @Override
        public String toString() {
            return "IdentityRegistrationRequest[REDACTED]";
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
    private final IdentityLoginAddressRepository addresses;
    private final Clock clock;

    JdbcIdentityRegistrations(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = DomainChecks.requireNonNull(jdbc, "Identity registration JDBC template");
        this.clock = DomainChecks.requireNonNull(clock, "Identity registration clock");
        capacity = new JdbcIdentityCapacity(jdbc);
        identities = new JdbcIdentityRepository(jdbc);
        registrations = new JdbcIdentityPendingRegistrationRepository(jdbc);
        challenges = new JdbcIdentityChallengeRepository(jdbc);
        credentials = new JdbcIdentityCredentialRepository(jdbc);
        addresses = new JdbcIdentityLoginAddressRepository(jdbc);
    }

    /**
     * Reserve before existence lookup; use the original owner's delivery spelling on repeat
     * requests.
     */
    Request request(LoginAddress address) {
        DomainChecks.requireNonNull(address, "Identity registration address");
        return mutate(
                () -> {
                    Instant now = clock.instant();
                    if (!capacity.reserve(Map.of(PENDING_REGISTRATIONS, 1L)))
                        throw new Failure(Code.CAPACITY);
                    var key = IdentityLoginKey.of(address.loginKey());
                    var current = addresses.forKey(key);
                    if (current.isPresent()) {
                        capacity.release(Map.of(PENDING_REGISTRATIONS, 1L));
                        var owned = current.get();
                        var identity =
                                lockedIdentity(owned.id())
                                        .orElseThrow(() -> new Failure(Code.INCONSISTENT_STATE));
                        if (identity.status() == IdentityStatus.PENDING_VERIFICATION) {
                            var pending =
                                    registrations
                                            .existing(owned.id())
                                            .orElseThrow(
                                                    () -> new Failure(Code.INCONSISTENT_STATE));
                            if (now.isBefore(pending.deadline().instant()))
                                return new Request(
                                        Outcome.PENDING,
                                        owned.id(),
                                        owned.deliveryAddress().encoded());
                        }
                        return new Request(Outcome.UNAVAILABLE, null, null);
                    }
                    var subject = createAt(now);
                    addresses.save(
                            IdentityLoginAddress.reconstitute(
                                    subject,
                                    key,
                                    IdentityDeliveryAddress.of(address.deliveryAddress()),
                                    now,
                                    now,
                                    0));
                    return new Request(Outcome.CREATED, subject, address.deliveryAddress());
                });
    }

    private UserId createAt(Instant now) {
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
                    if (addresses.existing(subject).isEmpty())
                        throw new Failure(Code.INCONSISTENT_STATE);
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
                        // Protected delivery envelopes must join this cleanup before public flows
                        // ship.
                        for (var purpose : IdentityChallengePurpose.values()) {
                            var challenge = challenges.current(subject, purpose);
                            challenge.ifPresent(
                                    value -> {
                                        challenges.delete(value);
                                        capacity.release(Map.of(ACTIONABLE_CHALLENGES, 1L));
                                    });
                        }
                        addresses.existing(subject).ifPresent(addresses::delete);
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
                                || failure instanceof DomainValidationException
                                || failure instanceof CredentialInputException
                                || failure instanceof UniqueConstraintViolationException)
                            throw new Failure(Code.STORAGE_UNAVAILABLE);
                        throw failure;
                    }
                });
    }
}
