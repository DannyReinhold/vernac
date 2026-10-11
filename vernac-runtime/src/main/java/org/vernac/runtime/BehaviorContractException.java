// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** A behavior implementation violated its generated non-null result contract. */
@NullMarked
public final class BehaviorContractException extends VernacTechnicalException {
    public BehaviorContractException(String method) {
        super("Behavior implementation returned null for " + method + ". Return a non-null value or Optional.empty() for an optional result.");
    }

    public static <T> T requireResult(@Nullable T result, String method) {
        if (result == null) throw new BehaviorContractException(method);
        return result;
    }
}
