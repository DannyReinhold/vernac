// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

import java.util.List;

public record RepositoryMethodNode(
        SourceLocation location,
        TypeNode returnType,
        String name,
        List<FieldNode> parameters,
        boolean isCustom,
        List<Predicate> predicates,
        List<Order> orders
) {
    public RepositoryMethodNode(SourceLocation location, TypeNode returnType, String name,
            List<FieldNode> parameters, boolean isCustom) {
        this(location, returnType, name, parameters, isCustom, List.of(), List.of());
    }
    public record Predicate(SourceLocation location, String field, String operator, String parameter) { }
    public record Order(SourceLocation location, String field, boolean descending) { }
}