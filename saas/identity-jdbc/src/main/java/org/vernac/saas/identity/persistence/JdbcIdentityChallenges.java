// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import static org.vernac.saas.identity.persistence.JdbcIdentityCapacity.Resource.ACTIONABLE_CHALLENGES;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.vernac.runtime.DomainChecks;
import org.vernac.saas.identity.domain.*;
import org.vernac.saas.identity.persistence.adapter.outbound.jdbc.JdbcIdentityChallengeRepository;
import org.vernac.saas.identity.persistence.domain.*;

import java.time.*;
import java.util.*;
import java.util.function.*;

/** Private lifecycle; no HTTP endpoint, mail shortcut, automatic bean or authentication grant. */
final class JdbcIdentityChallenges {
    enum Outcome {
        ISSUED,
        COOLDOWN,
        CAPACITY,
        UNAVAILABLE
    }

    static final class Issue {
        private final Outcome outcome;
        private final IdentityChallengeToken token;

        private Issue(Outcome outcome, IdentityChallengeToken token) {
            this.outcome = outcome;
            this.token = token;
        }

        Outcome outcome() {
            return outcome;
        }

        IdentityChallengeToken token() {
            if (token == null) throw new IllegalStateException("Identity challenge not issued");
            return token;
        }

        @Override
        public String toString() {
            return "IdentityChallengeIssue[" + outcome + "]";
        }
    }

    private static final String TABLE =
            "\"org.vernac.saas.identity.persistence\".\"IdentityChallenge\"";
    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcIdentityCapacity capacity;
    private final IdentityChallengeRepository repository;
    private final Clock clock;

    JdbcIdentityChallenges(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = DomainChecks.requireNonNull(jdbc, "Identity challenge JDBC template");
        this.clock = DomainChecks.requireNonNull(clock, "Identity challenge clock");
        capacity = new JdbcIdentityCapacity(jdbc);
        repository = new JdbcIdentityChallengeRepository(jdbc);
    }

    Issue issue(UserId subject, IdentityChallengePurpose purpose) {
        DomainChecks.requireNonNull(subject, "Identity challenge subject");
        DomainChecks.requireNonNull(purpose, "Identity challenge purpose");
        return mutate(
                () -> {
                    Instant now = clock.instant();
                    if (!eligible(subject, purpose)) return new Issue(Outcome.UNAVAILABLE, null);
                    var old = repository.current(subject, purpose);
                    if (old.isPresent() && now.isBefore(old.get().createdAt().plusSeconds(60)))
                        return new Issue(Outcome.COOLDOWN, null);
                    // A replacement reuses its existing charged slot, even at full capacity.
                    if (old.isEmpty() && !capacity.reserve(Map.of(ACTIONABLE_CHALLENGES, 1L)))
                        return new Issue(Outcome.CAPACITY, null);
                    old.ifPresent(repository::delete);
                    var token = IdentityChallengeToken.create();
                    var lifetime =
                            purpose == IdentityChallengePurpose.VERIFICATION
                                    ? Duration.ofHours(24)
                                    : Duration.ofMinutes(30);
                    repository.save(
                            IdentityChallenge.reconstitute(
                                    IdentityChallengeId.of(token.locator()),
                                    subject,
                                    purpose,
                                    IdentityChallengeVerifier.of(token.verifier()),
                                    IdentityChallengeExpiry.of(now.plus(lifetime)),
                                    now,
                                    now,
                                    0));
                    return new Issue(Outcome.ISSUED, token);
                });
    }

    /** Trusted local action only; callback must join this writer and perform no remote effects. */
    boolean consume(String input, IdentityChallengePurpose purpose, Consumer<UserId> action) {
        DomainChecks.requireNonNull(purpose, "Identity challenge purpose");
        DomainChecks.requireNonNull(action, "Identity challenge action");
        var token = IdentityChallengeToken.parse(input);
        if (token == null) return false; // bounded parsing before any database access
        return mutate(
                () -> {
                    Instant now = clock.instant();
                    var found = repository.existing(IdentityChallengeId.of(token.locator()));
                    if (found.isEmpty()) return false;
                    var row = found.get();
                    if (row.purpose() != purpose
                            || !now.isBefore(row.expiresAt().instant())
                            || !token.matches(row.verifier().encoded())
                            || !eligible(row.subject(), purpose)) return false;
                    repository.delete(row);
                    capacity.release(Map.of(ACTIONABLE_CHALLENGES, 1L));
                    action.accept(row.subject());
                    return true;
                });
    }

    /** Explicit bounded physical cleanup; expiration alone does not refund storage. */
    int removeExpired(int batchSize) {
        if (batchSize < 1 || batchSize > 500)
            throw new IllegalArgumentException("Invalid identity cleanup batch");
        return mutate(
                () -> {
                    Instant now = clock.instant();
                    var ids =
                            jdbc.query(
                                    "SELECT \"id\" FROM "
                                            + TABLE
                                            + " WHERE (\"expiresAt\", \"@nanoRemainder:expiresAt\")"
                                            + " <= (:now, :nano) ORDER BY \"expiresAt\","
                                            + " \"@nanoRemainder:expiresAt\", \"id\" LIMIT :limit",
                                    Map.of(
                                            "now",
                                            java.sql.Timestamp.from(
                                                    now.truncatedTo(
                                                            java.time.temporal.ChronoUnit.MICROS)),
                                            "nano",
                                            now.getNano() % 1000,
                                            "limit",
                                            batchSize),
                                    (rs, n) -> IdentityChallengeId.of(rs.getObject(1, UUID.class)));
                    for (var id : ids) repository.delete(repository.byId(id));
                    if (!ids.isEmpty())
                        capacity.release(Map.of(ACTIONABLE_CHALLENGES, (long) ids.size()));
                    return ids.size();
                });
    }

    private boolean eligible(UserId subject, IdentityChallengePurpose purpose) {
        var statuses =
                jdbc.query(
                        "SELECT \"status\" FROM"
                            + " \"org.vernac.saas.identity.persistence\".\"Identity\" WHERE \"id\""
                            + " = :id FOR UPDATE",
                        Map.of("id", subject.value()),
                        (rs, n) -> rs.getString(1));
        return statuses.size() == 1
                && statuses.getFirst()
                        .equals(
                                purpose == IdentityChallengePurpose.VERIFICATION
                                        ? "PENDING_VERIFICATION"
                                        : "ACTIVE");
    }

    private <T> T mutate(Supplier<T> work) {
        return capacity.withBudgetLock(
                () -> {
                    try {
                        return work.get();
                    } catch (RuntimeException | Error failure) {
                        // Also poison the ambient transaction when the caller catches a failed
                        // action.
                        var source = jdbc.getJdbcTemplate().getDataSource();
                        ((ConnectionHolder) TransactionSynchronizationManager.getResource(source))
                                .setRollbackOnly();
                        if (failure instanceof DataAccessException)
                            throw new IllegalStateException(
                                    "Identity challenge storage unavailable");
                        throw failure;
                    }
                });
    }
}
