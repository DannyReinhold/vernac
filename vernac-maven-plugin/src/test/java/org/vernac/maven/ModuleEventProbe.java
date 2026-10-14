// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.vernac.runtime.DomainValidationException;

/** Visible to generated test code; records synchronous execution, not persisted state. */
public final class ModuleEventProbe {
    private ModuleEventProbe() { }
    static int calls;
    static boolean reject;
    static void reset() {calls=0;reject=false;}
    public static void observe(String value) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Listener escaped the usecase transaction");
        if (!value.equals("fixture")) throw new IllegalStateException("Wrong exported event payload");
        calls++;
        if(reject) throw new DomainValidationException("Synchronous module listener rejected the operation");
    }
}
