// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

public record IdReferenceNode(
        SourceLocation location,
        TypeNode type
) implements AstNode {
    public String fieldName() {
        return "id";
    }
}