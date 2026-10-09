// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.persistence;

import java.util.List;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.symbols.ResolvedType;

/** Executable binding metadata captured while the schema is built, not reconstructed from SQL. */
public record StoragePlan(RepositoryNode repository, String repositoryNamespace, AggregateNode aggregate,
                          String aggregateNamespace, List<Relation> relations) {
    public record Relation(SchemaModel.Table table, String predicate, int depth, List<Property> properties) { }
    public record Property(String name, Value value) { }
    public record Value(ResolvedType type, boolean optional, String witness, String marker,
                        List<String> columns, List<Property> children, String relation,
                        String ownerColumn, String ownerKey, boolean ordered) { }
}
