// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime;

/** A repository query declared as Optional matched more than one aggregate. */
@org.jspecify.annotations.NullMarked
public final class NonUniqueQueryResultException extends VernacTechnicalException {
    public NonUniqueQueryResultException(String query) {
        super("Repository query expected at most one aggregate: " + query);
    }
}
