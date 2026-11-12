// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.authorization;

import java.util.Optional;
import org.jspecify.annotations.NullMarked;

/** Trusted infrastructure; absence means no verified enabled human/technical actor. */
@NullMarked
@FunctionalInterface
public interface ActorResolver {
    Optional<VerifiedActor> resolve();
}
