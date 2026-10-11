// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.dto;
import java.util.List;
import org.vernac.compiler.ast.*;
/** Fully resolved adapter-local transport model. Generation performs no type-name lookup. */
public record ResolvedSchema(SchemaNode source, List<Field> fields) {
    public ResolvedSchema { fields = List.copyOf(fields); }
    public record Field(SchemaFieldNode source, String externalName, Shape shape) { }
    public sealed interface Shape permits Scalar, Nested, Sequence { }
    public record Scalar(Class<?> javaType) implements Shape { }
    public record Nested(String schemaName) implements Shape { }
    public record Sequence(Shape element) implements Shape { }
}
