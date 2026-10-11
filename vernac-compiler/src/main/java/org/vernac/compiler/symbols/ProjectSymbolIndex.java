// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.symbols;

import java.util.*;

/**
 * Immutable project-wide declaration index, built before resolving references.
 * Imports never trigger file loading or recursive expansion in this index.
 */
public final class ProjectSymbolIndex {
    public enum ProblemKind { RESERVED_NAME, DUPLICATE_DECLARATION }

    /** All declarations involved are retained, including both duplicate origins. */
    public record Problem(ProblemKind kind, String message, List<TypeSymbol> declarations) {
        public Problem {
            declarations = List.copyOf(declarations);
        }
    }

    private static final Comparator<TypeSymbol> SOURCE_ORDER = Comparator
            .comparing((TypeSymbol s) -> s.sourceFile().toString())
            .thenComparingInt(s -> s.location().line())
            .thenComparingInt(s -> s.location().column())
            .thenComparing(s -> s.kind().name());

    private final Map<String, TypeSymbol> symbols;
    private final Map<String, List<TypeSymbol>> namespaces;
    private final List<Problem> problems;

    public ProjectSymbolIndex(Collection<TypeSymbol> declarations) {
        Map<String, List<TypeSymbol>> groups = new TreeMap<>();
        for (TypeSymbol symbol : declarations) {
            groups.computeIfAbsent(symbol.identity().qualifiedName(), key -> new ArrayList<>())
                    .add(symbol);
        }
        Map<String, TypeSymbol> accepted = new TreeMap<>();
        Map<String, List<TypeSymbol>> byNamespace = new TreeMap<>();
        List<Problem> issues = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            List<TypeSymbol> group = entry.getValue().stream().sorted(SOURCE_ORDER).toList();
            TypeSymbol first = group.get(0);
            if (BuiltinTypes.names().contains(first.identity().name())) {
                issues.add(new Problem(ProblemKind.RESERVED_NAME,
                        "Type name '" + first.identity().name()
                                + "' is reserved for a built-in type. Choose a domain-specific name.", group));
            } else if (group.size() > 1) {
                issues.add(new Problem(ProblemKind.DUPLICATE_DECLARATION,
                        "Duplicate type declaration '" + entry.getKey() + "'.", group));
            } else {
                accepted.put(entry.getKey(), first);
                byNamespace.computeIfAbsent(first.identity().namespace(), key -> new ArrayList<>())
                        .add(first);
            }
        }
        symbols = Collections.unmodifiableMap(accepted);
        byNamespace.replaceAll((key, value) -> List.copyOf(value));
        namespaces = Collections.unmodifiableMap(byNamespace);
        problems = List.copyOf(issues);
    }

    /** Exact Vernac identity lookup. Unknown and invalid declarations have no symbol. */
    public Optional<TypeSymbol> find(String qualifiedName) {
        return Optional.ofNullable(symbols.get(Objects.requireNonNull(qualifiedName)));
    }

    /** Direct members only; child namespaces are not included. */
    public List<TypeSymbol> inNamespace(String namespace) {
        return namespaces.getOrDefault(Objects.requireNonNull(namespace), List.of());
    }

    public List<Problem> problems() {
        return problems;
    }
}
