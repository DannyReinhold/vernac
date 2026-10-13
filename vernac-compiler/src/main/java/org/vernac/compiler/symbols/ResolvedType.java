// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.symbols;

import java.util.Objects;

/** Semantic identity after lookup. Java code generation does not guess from source strings. */
public sealed interface ResolvedType permits ResolvedType.Page, ResolvedType.Builtin, ResolvedType.Declared, ResolvedType.VoidReturn {
    /** A completed application result, never a persisted domain field. */
    record Page(org.vernac.compiler.ast.TypeNode.PageKind kind, Declared collection, Declared element) implements ResolvedType {
        public Page { Objects.requireNonNull(kind); Objects.requireNonNull(collection); Objects.requireNonNull(element); }
    }
    /** Only valid as a method return type. */
    record VoidReturn() implements ResolvedType { }
    record Builtin(Class<?> javaType) implements ResolvedType {
        public Builtin {
            Objects.requireNonNull(javaType);
            if (BuiltinTypes.find(javaType.getSimpleName()).filter(javaType::equals).isEmpty()) {
                throw new IllegalArgumentException("Not an approved Vernac built-in: " + javaType.getName());
            }
        }
    }

    record Declared(TypeSymbol symbol) implements ResolvedType {
        public Declared {
            Objects.requireNonNull(symbol);
        }
    }
}
