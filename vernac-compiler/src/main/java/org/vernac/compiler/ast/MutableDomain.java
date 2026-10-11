// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

import java.util.List;
import java.util.Optional;

/** Shared structural view of entities and aggregate roots. */
public record MutableDomain(TopLevelDefinition definition, String name, IdReferenceNode id,
                            List<FieldNode> fields, List<MethodNode> methods,
                            List<JavaImportNode> imports, boolean aggregate) {
    public static Optional<MutableDomain> of(TopLevelDefinition definition) {
        if (definition instanceof EntityNode n) return Optional.of(new MutableDomain(n, n.name(), n.idDefinition(), n.fields(), n.methods(), n.javaImports(), false));
        if (definition instanceof AggregateNode n) return Optional.of(new MutableDomain(n, n.name(), n.idDefinition(), n.fields(), n.methods(), n.javaImports(), true));
        return Optional.empty();
    }
}
