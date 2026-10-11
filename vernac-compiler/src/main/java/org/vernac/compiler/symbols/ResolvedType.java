// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.symbols;

import java.util.Objects;

/** Semantic identity after lookup. Java code generation does not guess from source strings. */
public sealed interface ResolvedType permits ResolvedType.Builtin, ResolvedType.Declared, ResolvedType.VoidReturn {
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
