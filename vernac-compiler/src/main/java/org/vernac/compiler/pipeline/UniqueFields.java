// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.symbols.*;

import java.util.*;

/** Validated domain requirement only; persistence adoption must supply enforcement. */
final class UniqueFields {
    private static final Set<String> SCALARS =
            Set.of(
                    "String", "UUID", "boolean", "Boolean", "byte", "Byte", "short", "Short", "int",
                    "Integer", "long", "Long");

    List<CompilerDiagnostic> validate(VernacProject project, Map<TypeNode, ResolvedType> types) {
        var values = new HashMap<String, ValueObjectNode>();
        for (var source : project.sources())
            for (var value : source.unit().valueObjects())
                values.put(source.unit().namespace() + "." + value.name(), value);
        var errors = new ArrayList<CompilerDiagnostic>();
        for (var source : project.sources())
            for (var definition : source.unit().definitions()) {
                if (!(definition instanceof AggregateNode aggregate)) continue;
                for (var field : aggregate.fields()) {
                    if (!field.isUnique()) continue;
                    var type = types.get(field.type());
                    if (type == null) continue; // retain the primary unresolved-type diagnostic
                    boolean supported = !field.type().isOptional();
                    if (type instanceof ResolvedType.Declared declared) {
                        var kind = declared.symbol().kind();
                        if (kind == TypeSymbol.Kind.VALUE_OBJECT) {
                            var value = values.get(declared.symbol().identity().qualifiedName());
                            supported &= value != null && value.fields().size() == 1;
                            if (supported) {
                                var scalar = value.fields().getFirst();
                                supported =
                                        !scalar.type().isOptional()
                                                && types.get(scalar.type())
                                                        instanceof ResolvedType.Builtin builtin
                                                && SCALARS.contains(
                                                        builtin.javaType().getSimpleName());
                            }
                        } else
                            supported &= kind == TypeSymbol.Kind.ID || kind == TypeSymbol.Kind.ENUM;
                    } else supported = false;
                    if (!supported)
                        errors.add(
                                CompilerDiagnostic.error(
                                        field.location(),
                                        "Unique aggregate field '"
                                                + field.name()
                                                + "' requires a non-optional Vernac ID, enum, or"
                                                + " value directly wrapping one required String,"
                                                + " UUID, boolean or integer scalar"
                                                + " (byte/short/int/long)."));
                }
            }
        return errors;
    }
}
