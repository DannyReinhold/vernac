// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.authorization;

import org.jspecify.annotations.NullMarked;

/** Application policy. No business mutations, reservations, payments or usage consumption. */
@NullMarked
@FunctionalInterface
public interface PermissionEvaluator {
    PermissionDecision evaluate(PermissionRequest request);
}
