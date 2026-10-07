// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.symbols;

import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.ast.SourceLocation;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Lookup failures are diagnostics, never guessed Java class names. */
public record TypeLookup(Optional<ResolvedType> type, List<CompilerDiagnostic> diagnostics) {
    public TypeLookup {
        Objects.requireNonNull(type);
        diagnostics = List.copyOf(diagnostics);
    }

    public static TypeLookup found(ResolvedType type) {
        return new TypeLookup(Optional.of(type), List.of());
    }

    public static TypeLookup error(SourceLocation location, String message) {
        return new TypeLookup(Optional.empty(), List.of(CompilerDiagnostic.error(location, message)));
    }
}
