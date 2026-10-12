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
 * Project-wide resolved domain types, queries, schemas, mappings and REST bindings.
 * Embedded Java still requires downstream Java compilation.
 */
public record ResolvedProject(VernacProject project,
                              Map<Path, FileTypeScope> scopes,
                              Map<TypeNode, ResolvedType> types,
                              List<CompilerDiagnostic> diagnostics,
                              List<TypeSymbol> deferredTypes,
                              Map<org.vernac.compiler.ast.RepositoryMethodNode, org.vernac.compiler.query.ResolvedQuery> queries,
                              Map<org.vernac.compiler.ast.OutboundAdapterNode,List<org.vernac.compiler.dto.ResolvedSchema>> schemas,
                              Map<org.vernac.compiler.ast.OutboundAdapterNode,List<org.vernac.compiler.mapping.ResolvedMapping>> mappings,
                              Map<org.vernac.compiler.ast.MethodNode, org.vernac.compiler.rest.ResolvedRestOperation> restOperations) {
    public ResolvedProject(VernacProject project, Map<Path, FileTypeScope> scopes, Map<TypeNode, ResolvedType> types, List<CompilerDiagnostic> diagnostics, List<TypeSymbol> deferredTypes) {
        this(project, scopes, types, diagnostics, deferredTypes, Map.of(), Map.of(), Map.of(), Map.of());
    }
    public ResolvedProject {
        Objects.requireNonNull(project);
        scopes = Collections.unmodifiableMap(new LinkedHashMap<>(scopes));
        types = Collections.unmodifiableMap(new LinkedHashMap<>(types));
        diagnostics = List.copyOf(diagnostics);
        deferredTypes = List.copyOf(deferredTypes);
        queries = Map.copyOf(queries);
        schemas = Map.copyOf(schemas);
        mappings = Map.copyOf(mappings);
        restOperations = Map.copyOf(restOperations);
    }

    public ResolvedType typeOf(TypeNode fieldType) {
        ResolvedType resolved = types.get(fieldType);
        if (resolved == null) {
            throw new IllegalArgumentException("No resolved type at " + fieldType.location());
        }
        return resolved;
    }
}
