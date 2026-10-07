// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.symbols;

import org.vernac.language.VernacNames;
import java.util.Objects;

/** A Vernac identity; neither a source filename nor a generated Java package. */
public record TypeIdentity(String namespace, String name) {
    public TypeIdentity {
        Objects.requireNonNull(namespace);
        Objects.requireNonNull(name);
        if (!VernacNames.isNamespace(namespace)) {
            throw new IllegalArgumentException("Invalid namespace: " + namespace);
        }
        if (!VernacNames.isTypeName(name)) {
            throw new IllegalArgumentException("Invalid type name: " + name);
        }
    }

    public String qualifiedName() {
        return namespace + "." + name;
    }
}
