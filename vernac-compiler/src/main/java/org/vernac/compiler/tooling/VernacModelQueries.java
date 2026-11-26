// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.tooling;

import org.vernac.compiler.ast.FieldNode;
import org.vernac.compiler.ast.MethodNode;
import org.vernac.compiler.ast.MutableDomain;
import org.vernac.compiler.ast.SourceLocation;
import org.vernac.compiler.ast.TypeNode;
import org.vernac.compiler.ast.ValueObjectNode;
import org.vernac.compiler.pipeline.ResolvedProject;
import org.vernac.compiler.symbols.ResolvedType;
import org.vernac.compiler.symbols.TypeSymbol;

import java.util.List;
import java.util.Optional;

/** Structured model reads against an already resolved project snapshot. */
public final class VernacModelQueries {
    private final ResolvedProject project;

    public VernacModelQueries(ResolvedProject project) {
        this.project = project;
    }

    public record TypeReference(String spelling, Optional<String> identity, boolean optional) { }

    public record Field(String name, TypeReference type, boolean mutable, SourceLocation location) { }

    public record Method(String name, String access, MethodNode.Mode effect, List<Field> parameters,
                         TypeReference returns, SourceLocation location) {
        public Method {
            parameters = List.copyOf(parameters);
        }
    }

    public record Description(String identity, TypeSymbol.Kind kind, boolean external,
                              SourceLocation location, Optional<TypeReference> idType,
                              List<Field> fields, List<Method> methods, List<String> enumConstants) {
        public Description {
            fields = List.copyOf(fields);
            methods = List.copyOf(methods);
            enumConstants = List.copyOf(enumConstants);
        }
    }

    /** Describes a value, enum, entity or aggregate by its exact Vernac identity. */
    public Optional<Description> describeType(String identity) {
        var symbol = project.project().symbols().find(identity).orElse(null);
        if (symbol == null) return Optional.empty();
        var source = project.project().sources().stream()
                .filter(candidate -> candidate.path().equals(symbol.sourceFile())).findFirst().orElse(null);
        if (source == null) return Optional.empty();

        String name = symbol.identity().name();
        for (var definition : source.unit().definitions()) {
            if (!definition.location().equals(symbol.location())) continue;
            if (definition instanceof ValueObjectNode value && value.name().equals(name)) {
                return Optional.of(describe(symbol, source.external(), Optional.empty(), value.fields(),
                        value.methods(), value.enumConstants().stream().map(constant -> constant.name()).toList()));
            }
            var mutable = MutableDomain.of(definition);
            if (mutable.isPresent() && mutable.orElseThrow().name().equals(name)) {
                var domain = mutable.orElseThrow();
                return Optional.of(describe(symbol, source.external(), Optional.of(type(domain.id().type())),
                        domain.fields(), domain.methods(), List.of()));
            }
        }
        return Optional.empty();
    }

    private Description describe(TypeSymbol symbol, boolean external, Optional<TypeReference> idType,
                                 List<FieldNode> fields, List<MethodNode> methods, List<String> enumConstants) {
        return new Description(symbol.identity().qualifiedName(), symbol.kind(), external, symbol.location(), idType,
                fields.stream().map(this::field).toList(), methods.stream().map(this::method).toList(), enumConstants);
    }

    private Field field(FieldNode field) {
        return new Field(field.name(), type(field.type()), field.isMutable(), field.location());
    }

    private Method method(MethodNode method) {
        return new Method(method.name(), method.accessModifier(), method.mode(),
                method.parameters().stream().map(this::field).toList(), type(method.returnType()), method.location());
    }

    private TypeReference type(TypeNode type) {
        var resolved = project.types().get(type);
        var identity = switch (resolved) {
            case ResolvedType.Declared declared -> Optional.of(declared.symbol().identity().qualifiedName());
            case ResolvedType.Builtin builtin -> Optional.of(builtin.javaType().getName());
            case ResolvedType.VoidReturn ignored -> Optional.of("void");
            case ResolvedType.Page page -> Optional.of(page.collection().symbol().identity().qualifiedName());
            case null -> Optional.<String>empty();
        };
        return new TypeReference(type.name(), identity, type.isOptional());
    }
}
