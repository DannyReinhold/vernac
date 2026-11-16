// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.payments.persistence;

import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.vernac.saas.identity.domain.AccountId;
import org.vernac.saas.subscriptions.SubscriptionLedger;
import org.vernac.saas.subscriptions.SubscriptionFailure;

/** Explicit payment/subscription composition: unresolved financial work fences user changes.
 * Install on the SubscriptionLedger that is also used by PaymentFlow. Uses the same datasource.
 * No provider I/O, history pagination or customer refund policy is involved.
 */
public final class PaymentLifecyclePolicy implements SubscriptionLedger.LifecyclePolicy {
    private final NamedParameterJdbcTemplate jdbc;
    public PaymentLifecyclePolicy(NamedParameterJdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }
    @Override public void requireChangeAllowed(AccountId account) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !TransactionSynchronizationManager.hasResource(jdbc.getJdbcTemplate().getDataSource()))
            throw new SubscriptionFailure(SubscriptionFailure.Code.CONFIGURATION);
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))",
                Map.of("key", "vernac.payments/account/" + account.asString()));
        Boolean unresolved = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM \"org.vernac.saas.payments.persistence\".\"PaymentOrder\" "
                + "WHERE \"account\"=:account AND (\"status\" IN ('OPEN','PAID','REVIEW') OR \"reason\"<>'NONE'))",
                Map.of("account", account.value()), Boolean.class);
        if (!Boolean.FALSE.equals(unresolved))
            throw new SubscriptionFailure(SubscriptionFailure.Code.INVALID_TRANSITION);
    }
}
