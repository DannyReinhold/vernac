// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.ast;

import java.util.Objects;

/** Source name plus one-based line and column; zero denotes an unknown position. */
public record SourceLocation(String sourceName, int line, int column) {
    public static final SourceLocation UNKNOWN = new SourceLocation("<unknown>", 0, 0);

    public SourceLocation {
        Objects.requireNonNull(sourceName);
        if (line < 0 || column < 0) {
            throw new IllegalArgumentException("Source coordinates must not be negative");
        }
    }

    public SourceLocation(int line, int column) {
        this("<memory>", line, column);
    }

    @Override
    public String toString() {
        return sourceName + ":" + line + ":" + column;
    }
}
