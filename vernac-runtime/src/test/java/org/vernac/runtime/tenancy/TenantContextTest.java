// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.tenancy;

import static org.junit.jupiter.api.Assertions.*;
import static org.vernac.runtime.tenancy.TenancyException.Code.*;

import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TenantContextTest {
    private final TenantId a = TenantId.of(UUID.randomUUID());
    private final TenantId b = TenantId.of(UUID.randomUUID());

    @AfterEach void noLeaks() {
        assertTrue(TenantContext.currentTenant().isEmpty());
        assertTrue(TenantContext.transactionTenant().isEmpty());
    }

    private static void code(TenancyException.Code expected, Runnable operation) {
        assertEquals(expected, assertThrows(TenancyException.class, operation::run).code());
    }

    @Test void selectionIsExplicitLifoAndRestorableWithoutStartingTransactions() {
        code(TENANT_CONTEXT_REQUIRED, TenantContext::requireTenant);
        var outer = TenantContext.openVerified(a);
        var inner = TenantContext.openVerified(b);
        code(TENANT_SCOPE_ORDER_INVALID, outer::close);
        assertEquals(b, TenantContext.requireTenant());
        inner.close(); inner.close();
        assertEquals(a, TenantContext.requireTenant());
        outer.close(); outer.close();
    }

    @Test void scopeIsThreadOwnedAndDoesNotPropagateToOtherThreads() throws Exception {
        try (var scope = TenantContext.openVerified(a); var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> {
                assertTrue(TenantContext.currentTenant().isEmpty());
                code(TENANT_SCOPE_THREAD_MISMATCH, scope::close);
            }).get();
            assertEquals(a, TenantContext.requireTenant());
        }
    }

    @Test void pinRequiresSynchronizedTransactionAndLiveSelection() {
        code(TENANT_TRANSACTION_REQUIRED, TenantContext::bindToCurrentTransaction);
        new TransactionTemplate(new Manager()).executeWithoutResult(status ->
                code(TENANT_CONTEXT_REQUIRED, TenantContext::bindToCurrentTransaction));
    }

    @Test void repeatedBindingIsStableUntilCommitAndDoesNotConsumeUnrelatedResources() {
        Object unrelatedKey = new Object(), unrelatedValue = new Object();
        try (var scope = TenantContext.openVerified(a)) {
            try {
                new TransactionTemplate(new Manager()).executeWithoutResult(status -> {
                    TransactionSynchronizationManager.bindResource(unrelatedKey, unrelatedValue);
                    assertEquals(a, TenantContext.bindToCurrentTransaction());
                    assertEquals(a, TenantContext.bindToCurrentTransaction());
                    try (var nested = TenantContext.openVerified(a)) { assertEquals(a, TenantContext.requireTenant()); }
                    code(TENANT_CONTEXT_MISMATCH, () -> TenantContext.openVerified(b));
                });
                assertSame(unrelatedValue, TransactionSynchronizationManager.getResource(unrelatedKey));
                assertTrue(TenantContext.transactionTenant().isEmpty());
                assertEquals(a, TenantContext.requireTenant());
            } finally {
                TransactionSynchronizationManager.unbindResourceIfPossible(unrelatedKey);
            }
        }
    }

    @Test void selectionOpenedInsideTransactionRemainsPinnedAfterScopeClose() {
        new TransactionTemplate(new Manager()).executeWithoutResult(status -> {
            try (var scope = TenantContext.openVerified(a)) {
                assertEquals(a, TenantContext.transactionTenant().orElseThrow());
            }
            code(TENANT_CONTEXT_MISMATCH, () -> TenantContext.openVerified(b));
        });
    }

    @Test void rollbackCleansThePinButDoesNotCloseTheCallersScope() {
        try (var scope = TenantContext.openVerified(a)) {
            assertThrows(IllegalStateException.class, () -> new TransactionTemplate(new Manager()).executeWithoutResult(status -> {
                TenantContext.bindToCurrentTransaction();
                throw new IllegalStateException("fixture rollback");
            }));
            assertTrue(TenantContext.transactionTenant().isEmpty());
            assertEquals(a, TenantContext.requireTenant());
        }
    }

    @Test void scopeCloseDoesNotUnpinAnActiveTransactionOrAuthorizeAnotherTenant() {
        new TransactionTemplate(new Manager()).executeWithoutResult(status -> {
            try (var scope = TenantContext.openVerified(a)) { TenantContext.bindToCurrentTransaction(); }
            assertEquals(a, TenantContext.transactionTenant().orElseThrow());
            code(TENANT_CONTEXT_REQUIRED, TenantContext::requireTenant);
            code(TENANT_CONTEXT_MISMATCH, () -> TenantContext.openVerified(b));
            try (var same = TenantContext.openVerified(a)) { assertEquals(a, TenantContext.requireTenant()); }
        });
    }

    @Test void closingAScopeCannotRestoreADifferentTenantInsideAPinnedTransaction() {
        try (var outer = TenantContext.openVerified(a); var inner = TenantContext.openVerified(b)) {
            new TransactionTemplate(new Manager()).executeWithoutResult(status -> {
                TenantContext.bindToCurrentTransaction();
                code(TENANT_CONTEXT_MISMATCH, inner::close);
                assertEquals(b, TenantContext.requireTenant());
            });
        }
    }

    // Lifecycle unit fixture only. Actual JDBC and REQUIRES_NEW proof remains in the PostgreSQL spike.
    private static final class Manager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object tx, TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) {}
        @Override protected void doRollback(DefaultTransactionStatus status) {}
    }
}
