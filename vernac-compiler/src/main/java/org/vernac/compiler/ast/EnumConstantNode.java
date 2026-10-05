// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

import java.util.Optional;

public record EnumConstantNode(
        SourceLocation location,
        String name,
        Optional<String> customDbValue
) implements AstNode {

    /**
     * Liefert den Persistenzwert: Entweder den explizit definierten oder standardmäßig den Namen.
     */
    public String effectiveDbValue() {
        return customDbValue.orElse(name);
    }
}