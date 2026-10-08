// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.symbols;

/** Reviewed Java target identity shared by generation and API validation. */
public final class JavaTypeNames {
    private JavaTypeNames() { }

    public static String domainPackage(TypeIdentity identity) {
        return identity.namespace() + ".domain";
    }

    /** Erased parameter name for the currently supported non-generic resolved types. */
    public static String canonicalName(ResolvedType type) {
        return switch (type) {
            case ResolvedType.Builtin builtin -> builtin.javaType().getName();
            case ResolvedType.VoidReturn ignored -> "void";
            case ResolvedType.Declared declared -> switch (declared.symbol().kind()) {
                case ID, VALUE_OBJECT, ENUM, COLLECTION, ENTITY, AGGREGATE -> domainPackage(declared.symbol().identity())
                        + "." + declared.symbol().identity().name();
                default -> throw new IllegalArgumentException("No reviewed Java mapping for " + declared.symbol().kind());
            };
        };
    }
}
