// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.query;

import java.util.List;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.symbols.ResolvedType;

/** Semantic query contract; SQL columns are bound from the shared storage plan. */
public record ResolvedQuery(RepositoryMethodNode method, ResolvedType result, boolean singleton,
        List<Predicate> predicates, List<Order> orders) {
    public record Field(String name, ResolvedType type, boolean optional, String scalar, String accessor) { }
    public record Predicate(Field field, String operator, FieldNode parameter) { }
    public record Order(Field field, boolean descending) { }
}
