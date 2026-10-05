// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

public record SourceLocation(int line, int column) {
    public static final SourceLocation UNKNOWN = new SourceLocation(0, 0);

    @Override
    public String toString() {
        return line + ":" + column;
    }
}