// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import static org.vernac.saas.identity.persistence.JdbcIdentityCapacity.Resource.*;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.vernac.saas.identity.persistence.adapter.outbound.jdbc.JdbcIdentityMailJobRepository;
import org.vernac.saas.identity.persistence.domain.*;

import java.util.Map;

/** Accounted job transitions in the shared writer; leased payloads cannot be refunded early. */
final class JdbcIdentityMailLedger {
    private final JdbcIdentityCapacity capacity;
    private final IdentityMailJobRepository jobs;

    JdbcIdentityMailLedger(NamedParameterJdbcTemplate jdbc) {
        capacity = new JdbcIdentityCapacity(jdbc);
        jobs = new JdbcIdentityMailJobRepository(jdbc);
    }

    void cancel(IdentityChallengeId challenge) {
        capacity.withBudgetLock(
                () -> {
                    jobs.forChallenge(challenge)
                            .ifPresent(
                                    job -> {
                                        if (job.state() == IdentityMailState.READY) remove(job);
                                        else if (job.state() != IdentityMailState.CANCELLED) {
                                            job.cancel();
                                            jobs.save(job);
                                        }
                                    });
                    return null;
                });
    }

    void remove(IdentityMailJob job) {
        capacity.withBudgetLock(
                () -> {
                    jobs.delete(job);
                    capacity.release(
                            Map.of(
                                    UNSENT_MAIL_JOBS,
                                    1L,
                                    UNSENT_MAIL_BYTES,
                                    (long) job.envelope().encoded().length()));
                    return null;
                });
    }
}
