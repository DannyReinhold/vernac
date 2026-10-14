// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import java.util.concurrent.atomic.AtomicInteger;
import org.vernac.runtime.DomainValidationException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Fails once after a receiver's SQL write, to prove retry rolls back that write. */
public final class ModuleDeferredEventProbe {
    private ModuleDeferredEventProbe() { }
    static final AtomicInteger calls=new AtomicInteger();
    public static void afterWrite() {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Deferred receiver needs a processing transaction");
        if(calls.getAndIncrement()==0)throw new DomainValidationException("Deliberate first-attempt failure");
    }
}
