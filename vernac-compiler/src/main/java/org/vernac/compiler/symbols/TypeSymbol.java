// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.symbols;

import org.vernac.compiler.ast.SourceLocation;
import java.nio.file.Path;
import java.util.Objects;

/** A declaration's stable identity, semantic category, and source origin. */
public record TypeSymbol(TypeIdentity identity, Kind kind, Path sourceFile,
                         SourceLocation location) {
    public enum Kind {
        ID, VALUE_OBJECT, ENUM, COLLECTION, ENTITY, AGGREGATE,
        EVENT, REPOSITORY, PORT, USE_CASE, DOMAIN_SERVICE, LISTENER
    }

    public TypeSymbol {
        Objects.requireNonNull(identity);
        Objects.requireNonNull(kind);
        sourceFile = Objects.requireNonNull(sourceFile).normalize();
        Objects.requireNonNull(location);
    }
}
