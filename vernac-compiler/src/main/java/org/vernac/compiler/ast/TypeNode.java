// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

import java.util.List;

public record TypeNode(
        SourceLocation location,
        String name,
        List<TypeNode> typeArguments,
        boolean isOptional
) implements AstNode {
    public TypeNode(SourceLocation location, String name) {
        this(location, name, List.of(), false);
    }
}