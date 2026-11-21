// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Test-only rendezvous reached after a module write inside the active business transaction. */
public final class TenantModuleTransactionProbe {
    public static volatile CyclicBarrier barrier;
    private TenantModuleTransactionProbe() { }
    public static void reached() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new AssertionError("No business transaction");
        try { barrier.await(20, TimeUnit.SECONDS); }
        catch (Exception failed) { throw new IllegalStateException("Transaction rendezvous failed", failed); }
    }
}
