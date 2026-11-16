// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.billing.lifecycle;

import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.vernac.saas.identity.domain.AccountId;
import org.vernac.saas.identity.persistence.*;
import org.vernac.saas.identity.security.IdentityAccountException;
import org.vernac.saas.payments.persistence.*;

/** Explicit same-database composition. Lock order: Identity account, subscription, payment account.
 * No network I/O or nested ledger calls occur inside these hooks.
 */
public final class BillingAccountLifecycle implements IdentityAccounts.ClosurePolicy, PaymentFlow.AccountStatusGuard {
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    public BillingAccountLifecycle(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc=Objects.requireNonNull(jdbc);this.clock=Objects.requireNonNull(clock);
    }
    private void writer() {
        if(!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !TransactionSynchronizationManager.hasResource(jdbc.getJdbcTemplate().getDataSource()))
            throw new AccountAccessException(IdentityAccountException.Code.CONFIGURATION);
    }
    @Override public void requireActiveAccount(AccountId account) {
        writer();
        var rows=jdbc.queryForList("SELECT \"status\" FROM \"org.vernac.saas.identity.persistence\".\"Account\" WHERE \"id\"=:account FOR UPDATE",
                Map.of("account",account.value()),String.class);
        if(rows.size()!=1 || !rows.getFirst().equals("ACTIVE"))
            throw new PaymentProcessingFailure(PaymentProcessingFailure.Code.INVALID_STATE);
    }
    @Override public void requireClosureAllowed(AccountId account) {
        writer(); // IdentityAccounts has already locked and authorized the active account row.
        for(String key:List.of("vernac.subscription/"+account.asString(),"vernac.payments/account/"+account.asString()))
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))",Map.of("key",key));
        var now=clock.instant();
        var args=Map.<String,Object>of("account",account.value(),"now",java.sql.Timestamp.from(now.truncatedTo(java.time.temporal.ChronoUnit.MICROS)),"remainder",now.getNano()%1000);
        Boolean outstanding=jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM \"org.vernac.saas.payments.persistence\".\"PaymentOrder\" WHERE \"account\"=:account AND (\"status\" IN ('OPEN','PAID','REVIEW') OR \"reason\"<>'NONE')) "
                + "OR EXISTS (SELECT 1 FROM \"org.vernac.saas.payments.persistence\".\"PaymentRefund\" WHERE \"account\"=:account AND (\"state\" IN ('PENDING','UNKNOWN') OR \"reason\"<>'NONE')) "
                + "OR EXISTS (SELECT 1 FROM \"org.vernac.saas.subscriptions\".\"Subscription\" WHERE \"account\"=:account AND \"status\"<>'ENDED' AND "
                + "((\"renewalTerms.price\">0 AND \"renewal\"<>'CANCELLED') OR (\"terms.price\">0 AND (\"period.end\">:now OR (\"period.end\"=:now AND \"@nanoRemainder:period.end\">:remainder)))))",
                args,Boolean.class);
        if(!Boolean.FALSE.equals(outstanding))throw new AccountAccessException(IdentityAccountException.Code.BILLING_ACTIVE);
    }
}
