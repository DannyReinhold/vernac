// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

import java.util.List;
import java.util.Objects;

public record TypeNode(
        SourceLocation location,
        String name,
        List<TypeNode> typeArguments,
        boolean isOptional
) implements AstNode {
    public TypeNode {
        Objects.requireNonNull(location);
        Objects.requireNonNull(name);
        typeArguments = List.copyOf(typeArguments);
    }

    public TypeNode(SourceLocation location, String name) {
        this(location, name, List.of(), false);
    }
}