package org.vernac.compiler.generator;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import org.vernac.compiler.ast.TypeNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class TypeResolver {

    private static final Map<String, Class<?>> BUILTIN_TYPES = Map.ofEntries(
            Map.entry("String", String.class),
            Map.entry("Boolean", Boolean.class),
            Map.entry("Integer", Integer.class),
            Map.entry("Long", Long.class),
            Map.entry("Double", Double.class),
            Map.entry("BigDecimal", BigDecimal.class),
            Map.entry("BigInteger", BigInteger.class),
            Map.entry("UUID", UUID.class),
            Map.entry("LocalDate", LocalDate.class),
            Map.entry("LocalDateTime", LocalDateTime.class),
            Map.entry("Instant", Instant.class),
            Map.entry("List", List.class),
            Map.entry("Set", Set.class),
            Map.entry("Map", Map.class)
    );

    private TypeResolver() {
    }

    public static TypeName resolve(TypeNode typeNode, String defaultPackage) {
        String name = typeNode.name();

        TypeName rawType;
        if (BUILTIN_TYPES.containsKey(name)) {
            rawType = ClassName.get(BUILTIN_TYPES.get(name));
        } else if (name.contains(".")) {
            int lastDot = name.lastIndexOf('.');
            rawType = ClassName.get(name.substring(0, lastDot), name.substring(lastDot + 1));
        } else {
            rawType = ClassName.get(defaultPackage, name);
        }

        if (typeNode.typeArguments().isEmpty()) {
            return rawType;
        }

        TypeName[] typeArgs = typeNode.typeArguments().stream()
                .map(arg -> resolve(arg, defaultPackage))
                .toArray(TypeName[]::new);

        return ParameterizedTypeName.get((ClassName) rawType, typeArgs);
    }
}