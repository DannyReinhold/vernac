// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;
import org.vernac.compiler.ast.CollectionDeclaration;
import org.vernac.compiler.symbols.TypeIdentity;
import java.util.Optional;

public final class CollectionTypes {
    private CollectionTypes() { }
    public static Optional<CollectionDeclaration> find(VernacProject project, TypeIdentity identity) {
        return project.sources().stream().filter(s -> s.unit().namespace().equals(identity.namespace()))
                .flatMap(s -> s.unit().definitions().stream()).flatMap(d -> CollectionDeclaration.of(d).stream())
                .filter(c -> c.name().equals(identity.name())).findFirst();
    }
}
