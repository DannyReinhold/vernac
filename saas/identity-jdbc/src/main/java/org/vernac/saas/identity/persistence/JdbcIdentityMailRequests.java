// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import static org.vernac.saas.identity.persistence.JdbcIdentityCapacity.Resource.*;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.vernac.saas.identity.domain.UserId;
import org.vernac.saas.identity.persistence.adapter.outbound.jdbc.*;
import org.vernac.saas.identity.persistence.domain.*;
import org.vernac.saas.identity.security.credentials.LoginAddress;

import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Private atomic issuance. Caller supplies a bound writer and a pre-business encryption permit. */
final class JdbcIdentityMailRequests {
    private static final UUID NO_LEASE = new UUID(0, 0);
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final IdentityDeliveryCrypto crypto;
    private final JdbcIdentityCapacity capacity;
    private final JdbcIdentityRegistrations registrations;
    private final JdbcIdentityChallenges challenges;
    private final IdentityChallengeRepository challengeRows;
    private final IdentityLoginAddressRepository addresses;
    private final IdentityMailJobRepository jobs;

    JdbcIdentityMailRequests(
            NamedParameterJdbcTemplate jdbc, Clock clock, IdentityDeliveryCrypto crypto) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
        this.crypto = Objects.requireNonNull(crypto);
        capacity = new JdbcIdentityCapacity(jdbc);
        registrations = new JdbcIdentityRegistrations(jdbc, clock);
        challenges = new JdbcIdentityChallenges(jdbc, clock);
        challengeRows = new JdbcIdentityChallengeRepository(jdbc);
        addresses = new JdbcIdentityLoginAddressRepository(jdbc);
        jobs = new JdbcIdentityMailJobRepository(jdbc);
    }

    /** Normal completion is neutral whether a job was issued, throttled or ineligible. */
    void registration(LoginAddress address, IdentityDeliveryCrypto.Permit permit) {
        mutate(
                () -> {
                    reserve(); // Count, bytes AND potential challenge slot precede existence
                    // lookup.
                    var owner = registrations.request(address);
                    if (owner.outcome() == JdbcIdentityRegistrations.Outcome.UNAVAILABLE) {
                        releaseAll();
                        return null;
                    }
                    issue(
                            owner.subject(),
                            owner.deliveryAddress(),
                            IdentityChallengePurpose.VERIFICATION,
                            permit);
                    return null;
                });
    }

    void recovery(LoginAddress address, IdentityDeliveryCrypto.Permit permit) {
        mutate(
                () -> {
                    reserve();
                    var owner = addresses.forKey(IdentityLoginKey.of(address.loginKey()));
                    if (owner.isEmpty()) {
                        releaseAll();
                        return null;
                    }
                    issue(
                            owner.get().id(),
                            owner.get().deliveryAddress().encoded(),
                            IdentityChallengePurpose.RECOVERY,
                            permit);
                    return null;
                });
    }

    private void issue(
            UserId subject,
            String recipient,
            IdentityChallengePurpose purpose,
            IdentityDeliveryCrypto.Permit permit) {
        var issued = challenges.issueReserved(subject, purpose);
        if (issued.outcome() != JdbcIdentityChallenges.Outcome.ISSUED) {
            // issueReserved already released the unused challenge reservation.
            capacity.release(Map.of(UNSENT_MAIL_JOBS, 1L, UNSENT_MAIL_BYTES, 65_536L));
            return;
        }
        var challenge = challengeRows.byId(IdentityChallengeId.of(issued.token().locator()));
        Instant now = clock.instant();
        var id = IdentityMailJobId.create();
        var context =
                new IdentityDeliveryCrypto.Context(
                        id.value(),
                        subject.value(),
                        purpose,
                        challenge.id().value(),
                        challenge.expiresAt().instant());
        byte[] payload = new IdentityMailContent(recipient, issued.token()).encode();
        String envelope;
        try {
            envelope = crypto.seal(context, payload, permit);
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
        jobs.save(
                IdentityMailJob.reconstitute(
                        id,
                        subject,
                        purpose,
                        challenge.id(),
                        challenge.expiresAt(),
                        IdentityMailEnvelope.of(envelope),
                        IdentityMailState.READY,
                        IdentityMailAttempts.of(0),
                        IdentityMailTime.of(now),
                        IdentityMailLeaseToken.of(NO_LEASE),
                        IdentityMailTime.of(Instant.EPOCH),
                        now,
                        now,
                        0));
        if (envelope.length() < 65_536)
            capacity.release(Map.of(UNSENT_MAIL_BYTES, 65_536L - envelope.length()));
    }

    private void reserve() {
        if (!capacity.reserve(
                Map.of(
                        ACTIONABLE_CHALLENGES,
                        1L,
                        UNSENT_MAIL_JOBS,
                        1L,
                        UNSENT_MAIL_BYTES,
                        65_536L))) throw unavailable();
    }

    private void releaseAll() {
        capacity.release(
                Map.of(
                        ACTIONABLE_CHALLENGES,
                        1L,
                        UNSENT_MAIL_JOBS,
                        1L,
                        UNSENT_MAIL_BYTES,
                        65_536L));
    }

    private <T> T mutate(Supplier<T> work) {
        return capacity.withBudgetLock(
                () -> {
                    try {
                        return work.get();
                    } catch (RuntimeException failure) {
                        var holder =
                                TransactionSynchronizationManager.getResource(
                                        jdbc.getJdbcTemplate().getDataSource());
                        if (holder instanceof ConnectionHolder connection)
                            connection.setRollbackOnly();
                        throw unavailable();
                    }
                });
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("IDENTITY_DELIVERY_UNAVAILABLE");
    }
}
