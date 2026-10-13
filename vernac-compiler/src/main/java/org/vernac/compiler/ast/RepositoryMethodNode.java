// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

import java.util.List;

public record RepositoryMethodNode(
        SourceLocation location,
        TypeNode returnType,
        String name,
        List<FieldNode> parameters,
        List<Predicate> predicates,
        List<Order> orders,
        Expression condition,
        Pagination pagination,
        boolean counting
) {
    public enum Pagination { NONE, OFFSET, CURSOR }
    public boolean offset() { return pagination == Pagination.OFFSET; }
    public boolean cursor() { return pagination == Pagination.CURSOR; }
    public boolean paginated() { return pagination != Pagination.NONE; }
    public sealed interface Expression permits Predicate, Junction, Negation { }
    public record Junction(String operator, List<Expression> children) implements Expression { }
    public record Negation(Expression child) implements Expression { }
    public record Predicate(SourceLocation location, String field, String operator, String parameter) implements Expression { }
    public record Order(SourceLocation location, String field, boolean descending) { }
}