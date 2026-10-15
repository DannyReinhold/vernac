// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.util;

import org.vernac.compiler.ast.FieldNode;
import org.vernac.compiler.ast.TypeNode;
import org.vernac.compiler.symbols.JavaTypeNames;
import org.vernac.compiler.symbols.ResolvedType;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Java overload identity: erased parameter types; nullability and return types are not overloads.
 */
public final class JavaMethodSignatures {
    private JavaMethodSignatures() {}

    public static String parameter(TypeNode type, Map<TypeNode, ResolvedType> types) {
        var resolved = types.get(type);
        String name = resolved == null ? type.name() : JavaTypeNames.canonicalName(resolved);
        int generic = name.indexOf('<');
        return generic < 0 ? name : name.substring(0, generic);
    }

    public static String parameters(List<FieldNode> fields, Map<TypeNode, ResolvedType> types) {
        return String.join(",", fields.stream().map(f -> parameter(f.type(), types)).toList());
    }

    public static String signature(
            String name, List<FieldNode> fields, Map<TypeNode, ResolvedType> types) {
        return name + "(" + parameters(fields, types) + ")";
    }

    public static String implementation(
            String name,
            String receiver,
            List<FieldNode> fields,
            Map<TypeNode, ResolvedType> types) {
        return name
                + "("
                + String.join(
                        ",",
                        Stream.concat(
                                        receiver.isEmpty() ? Stream.empty() : Stream.of(receiver),
                                        fields.stream().map(f -> parameter(f.type(), types)))
                                .toList())
                + ")";
    }
}
