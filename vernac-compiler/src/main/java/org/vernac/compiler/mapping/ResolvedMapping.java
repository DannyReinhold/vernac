// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.mapping;
import java.util.List;
import com.palantir.javapoet.TypeName;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.dto.ResolvedSchema;
import org.vernac.compiler.symbols.TypeSymbol;

/** Resolved conversion plan shared by generation and tooling; no generator name guessing. */
public record ResolvedMapping(MappingNode source, Type target, Type input, List<Assignment> assignments) {
    public ResolvedMapping { assignments=List.copyOf(assignments); }
    public enum Kind { SCALAR, DOMAIN, DTO, SEQUENCE, RESULT }
    public record Type(String identity, TypeName javaType, Kind kind, TypeSymbol symbol,
            ResolvedSchema schema, Type element, boolean set) { }
    public record Field(String name,Type type,boolean optional,boolean patch) { }
    public record Path(Type root,List<Field> fields,Type result,boolean optional,boolean patch) {
        public Path { fields=List.copyOf(fields); }
    }
    public record Assignment(Field target,Path path,Conversion conversion) { }
    public sealed interface Conversion permits Identity,Unwrap,Wrap,Elements,Named { }
    public record Identity() implements Conversion { }
    public record Unwrap(Field field,Conversion next) implements Conversion { }
    public record Wrap(Type target,Conversion next) implements Conversion { }
    public record Elements(Type target,Conversion element) implements Conversion { }
    public record Named(String name) implements Conversion { }
}
