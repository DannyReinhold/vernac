// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.generator;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeName;
import org.vernac.compiler.symbols.ResolvedType;
import org.vernac.compiler.symbols.TypeIdentity;
import org.vernac.compiler.symbols.JavaTypeNames;

/** Converts semantic type identities to JavaPoet types without name lookup. */
public final class ResolvedJavaTypes {
    private ResolvedJavaTypes() { }

    public static ClassName domainClass(TypeIdentity identity) {
        return ClassName.get(JavaTypeNames.domainPackage(identity), identity.name());
    }

    public static TypeName javaType(ResolvedType type) {
        return switch (type) {
            case ResolvedType.Builtin builtin -> TypeName.get(builtin.javaType());
            case ResolvedType.VoidReturn ignored -> TypeName.VOID;
            case ResolvedType.Declared declared -> switch (declared.symbol().kind()) {
                case ID, VALUE_OBJECT, ENUM, COLLECTION, ENTITY, AGGREGATE -> domainClass(declared.symbol().identity());
                default -> throw new IllegalArgumentException("No reviewed Java mapping for " + declared.symbol().kind());
            };
        };
    }
}
