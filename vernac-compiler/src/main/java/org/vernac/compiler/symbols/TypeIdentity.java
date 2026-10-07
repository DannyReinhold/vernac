// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.symbols;

import javax.lang.model.SourceVersion;
import java.util.Objects;

/** A Vernac identity; neither a source filename nor a generated Java package. */
public record TypeIdentity(String namespace, String name) {
    public TypeIdentity {
        Objects.requireNonNull(namespace);
        Objects.requireNonNull(name);
        if (!SourceVersion.isName(namespace, SourceVersion.RELEASE_21)) {
            throw new IllegalArgumentException("Invalid namespace: " + namespace);
        }
        if (!SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name, SourceVersion.RELEASE_21)
                || java.util.Set.of("_", "var", "yield", "record", "sealed", "permits").contains(name)) {
            throw new IllegalArgumentException("Invalid type name: " + name);
        }
    }

    public String qualifiedName() {
        return namespace + "." + name;
    }
}
