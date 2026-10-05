// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

public record FieldNode(
        SourceLocation location,
        TypeNode type,
        String name,
        boolean isMutable
) implements AstNode {

    public FieldNode(SourceLocation location, TypeNode type, String name) {
        this(location, type, name, false);
    }
}