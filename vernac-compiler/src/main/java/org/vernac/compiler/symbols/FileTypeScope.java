// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.symbols;

import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.ast.CompilationUnitNode;
import org.vernac.compiler.ast.SourceLocation;
import java.util.*;
import java.util.stream.Collectors;

/** Immutable, file-local visibility over an already complete project index. */
public final class FileTypeScope {
    private final String namespace;
    private final ProjectSymbolIndex index;
    private final Map<String, TypeSymbol> explicit;
    private final Set<String> wildcards;
    private final List<CompilerDiagnostic> diagnostics;

    public FileTypeScope(CompilationUnitNode unit, ProjectSymbolIndex index, Set<String> knownNamespaces) {
        this.namespace = unit.namespace();
        this.index = Objects.requireNonNull(index);
        Map<String, TypeSymbol> explicit = new TreeMap<>();
        Set<String> wildcards = new TreeSet<>();
        Set<String> seen = new HashSet<>();
        List<CompilerDiagnostic> issues = new ArrayList<>();
        for (var declaration : unit.imports()) {
            String importedNamespace = declaration.namespace();
            if (importedNamespace.equals(namespace)) {
                issues.add(CompilerDiagnostic.error(declaration.location(),
                        "Cannot import the current namespace '" + namespace
                                + "' or one of its types. Its types are already available. Remove this import."));
                continue;
            }
            if (!seen.add(declaration.text())) {
                issues.add(CompilerDiagnostic.warning(declaration.location(),
                        "Redundant import '" + declaration.text() + "'. Remove the repeated import."));
                continue;
            }
            if (declaration.wildcard()) {
                if (!knownNamespaces.contains(importedNamespace)) {
                    issues.add(CompilerDiagnostic.error(declaration.location(),
                            "Unknown Vernac namespace '" + importedNamespace + "'. Wildcard imports are not recursive."));
                } else {
                    wildcards.add(importedNamespace);
                }
                continue;
            }
            var imported = index.find(declaration.target());
            if (imported.isEmpty()) {
                issues.add(CompilerDiagnostic.error(declaration.location(),
                        "Cannot import '" + declaration.target()
                                + "': no Vernac type with this identity exists in the project."
                                + " File-level imports do not import Java classes."));
                continue;
            }
            TypeSymbol symbol = imported.get();
            String simpleName = symbol.identity().name();
            var local = index.find(namespace + "." + simpleName);
            if (local.isPresent()) {
                issues.add(CompilerDiagnostic.error(declaration.location(),
                        "Import '" + declaration.target() + "' conflicts with local type "
                                + describe(local.get()) + ". Use a fully qualified Vernac reference instead."));
                continue;
            }
            TypeSymbol previous = explicit.putIfAbsent(simpleName, symbol);
            if (previous != null && !previous.identity().equals(symbol.identity())) {
                issues.add(CompilerDiagnostic.error(declaration.location(),
                        "Conflicting explicit imports for '" + simpleName + "': "
                                + describe(previous) + " and " + describe(symbol)
                                + ". Remove one import and qualify that type at its use sites."));
            }
        }
        this.explicit = Collections.unmodifiableMap(explicit);
        this.wildcards = Collections.unmodifiableSet(wildcards);
        this.diagnostics = List.copyOf(issues);
    }

    public List<CompilerDiagnostic> diagnostics() {
        return diagnostics;
    }

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> d.severity() == CompilerDiagnostic.Severity.ERROR);
    }

    public TypeLookup resolve(String name, SourceLocation location) {
        Objects.requireNonNull(name);
        Objects.requireNonNull(location);
        if (hasErrors()) {
            throw new IllegalStateException("Cannot resolve references in a scope with invalid imports");
        }
        if (name.contains(".")) {
            return index.find(name).map(symbol -> TypeLookup.found(new ResolvedType.Declared(symbol)))
                    .orElseGet(() -> TypeLookup.error(location,
                            "Unknown Vernac type '" + name + "'. Use its Vernac namespace, not a generated Java"
                                    + " package. Arbitrary Java field types are not supported."));
        }
        var builtin = BuiltinTypes.find(name);
        if (builtin.isPresent()) return TypeLookup.found(new ResolvedType.Builtin(builtin.get()));
        var local = index.find(namespace + "." + name);
        if (local.isPresent()) return TypeLookup.found(new ResolvedType.Declared(local.get()));
        if (explicit.containsKey(name)) return TypeLookup.found(new ResolvedType.Declared(explicit.get(name)));

        Map<String, TypeSymbol> candidates = new TreeMap<>();
        for (String wildcard : wildcards) {
            index.find(wildcard + "." + name).ifPresent(symbol ->
                    candidates.put(symbol.identity().qualifiedName(), symbol));
        }
        if (candidates.size() == 1) {
            return TypeLookup.found(new ResolvedType.Declared(candidates.values().iterator().next()));
        }
        if (candidates.size() > 1) {
            return TypeLookup.error(location, "Ambiguous type '" + name + "'. Candidates: "
                    + candidates.values().stream().map(FileTypeScope::describe).collect(Collectors.joining(", "))
                    + ". Add an explicit import or use a fully qualified Vernac type name.");
        }
        return TypeLookup.error(location, "Unknown type '" + name + "'. Declare it in this namespace, import"
                + " a Vernac type, use its fully qualified Vernac name, or choose an approved built-in type.");
    }

    private static String describe(TypeSymbol symbol) {
        return "'" + symbol.identity().qualifiedName() + "' (" + symbol.location() + ")";
    }
}
