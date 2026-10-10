// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.ast.TypeNode;
import org.vernac.compiler.symbols.FileTypeScope;
import org.vernac.compiler.symbols.ResolvedType;
import org.vernac.compiler.symbols.TypeSymbol;
import java.nio.file.Path;
import java.util.*;

/**
 * Import-validated project with resolved value-object field references.
 * Other declaration kinds remain indexed but are explicitly deferred.
 * This is not yet a fully validated or generated application.
 */
public record ResolvedProject(VernacProject project,
                              Map<Path, FileTypeScope> scopes,
                              Map<TypeNode, ResolvedType> types,
                              List<CompilerDiagnostic> diagnostics,
                              List<TypeSymbol> deferredTypes,
                              Map<org.vernac.compiler.ast.RepositoryMethodNode, org.vernac.compiler.query.ResolvedQuery> queries) {
    public ResolvedProject(VernacProject project, Map<Path, FileTypeScope> scopes, Map<TypeNode, ResolvedType> types, List<CompilerDiagnostic> diagnostics, List<TypeSymbol> deferredTypes) {
        this(project, scopes, types, diagnostics, deferredTypes, Map.of());
    }
    public ResolvedProject {
        Objects.requireNonNull(project);
        scopes = Collections.unmodifiableMap(new LinkedHashMap<>(scopes));
        types = Collections.unmodifiableMap(new LinkedHashMap<>(types));
        diagnostics = List.copyOf(diagnostics);
        deferredTypes = List.copyOf(deferredTypes);
        queries = Map.copyOf(queries);
    }

    public ResolvedType typeOf(TypeNode fieldType) {
        ResolvedType resolved = types.get(fieldType);
        if (resolved == null) {
            throw new IllegalArgumentException("No resolved type at " + fieldType.location());
        }
        return resolved;
    }
}
