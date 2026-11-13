// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.persistence;

import java.util.Objects;

/**
 * A declared aggregate field conflicted; contains model identities only, never database details.
 */
public final class UniqueConstraintViolationException extends RuntimeException {
    private final String aggregate;
    private final String field;

    public UniqueConstraintViolationException(String aggregate, String field) {
        super(
                "Unique aggregate field conflict: "
                        + Objects.requireNonNull(aggregate)
                        + "."
                        + Objects.requireNonNull(field),
                null,
                false,
                true);
        this.aggregate = aggregate;
        this.field = field;
    }

    public String aggregate() {
        return aggregate;
    }

    public String field() {
        return field;
    }
}
