// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.ast;
import java.util.List;
/** Adapter-local transport schema, not a domain type. */
public record SchemaNode(SourceLocation location, String name, String naming,
        List<SchemaFieldNode> fields) implements AstNode {
    public SchemaNode { fields = List.copyOf(fields); }
}
