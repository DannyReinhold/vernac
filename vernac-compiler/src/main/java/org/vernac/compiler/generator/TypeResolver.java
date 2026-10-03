package org.vernac.compiler.generator;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import org.vernac.compiler.ast.TypeNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.*;
import java.util.*;

public final class TypeResolver {

    private static final Map<String, TypeName> PRIMITIVES = Map.of(
            "int", TypeName.INT,
            "long", TypeName.LONG,
            "double", TypeName.DOUBLE,
            "float", TypeName.FLOAT,
            "boolean", TypeName.BOOLEAN,
            "byte", TypeName.BYTE,
            "short", TypeName.SHORT,
            "char", TypeName.CHAR,
            "void", TypeName.VOID
    );

    private static final Map<String, ClassName> KNOWN_JDK_TYPES = Map.ofEntries(
            Map.entry("String", ClassName.get(String.class)),
            Map.entry("Boolean", ClassName.get(Boolean.class)),
            Map.entry("Integer", ClassName.get(Integer.class)),
            Map.entry("Long", ClassName.get(Long.class)),
            Map.entry("Double", ClassName.get(Double.class)),
            Map.entry("Float", ClassName.get(Float.class)),
            Map.entry("BigDecimal", ClassName.get(BigDecimal.class)),
            Map.entry("BigInteger", ClassName.get(BigInteger.class)),
            Map.entry("UUID", ClassName.get(UUID.class)),
            Map.entry("Currency", ClassName.get(Currency.class)),
            Map.entry("Instant", ClassName.get(Instant.class)),
            Map.entry("LocalDate", ClassName.get(LocalDate.class)),
            Map.entry("LocalDateTime", ClassName.get(LocalDateTime.class)),
            Map.entry("LocalTime", ClassName.get(LocalTime.class)),
            Map.entry("ZonedDateTime", ClassName.get(ZonedDateTime.class)),
            Map.entry("Duration", ClassName.get(Duration.class)),
            Map.entry("List", ClassName.get(List.class)),
            Map.entry("Set", ClassName.get(Set.class)),
            Map.entry("Map", ClassName.get(Map.class)),
            Map.entry("Optional", ClassName.get(Optional.class))
    );

    private TypeResolver() {
    }

    public static TypeName resolve(TypeNode typeNode, String defaultPackage) {
        return resolve(typeNode, defaultPackage, Collections.emptyList());
    }

    public static TypeName resolve(TypeNode typeNode, String defaultPackage, List<String> explicitImports) {
        String name = typeNode.name();

        if (PRIMITIVES.containsKey(name)) {
            return PRIMITIVES.get(name);
        }

        TypeName baseType = null;

        // 1. Höchste Priorität: Expliziter Import aus der DSL (z. B. import com.foo.Currency;)
        for (String imp : explicitImports) {
            if (!imp.endsWith(".*")) {
                int lastDot = imp.lastIndexOf('.');
                if (lastDot >= 0 && imp.substring(lastDot + 1).equals(name)) {
                    baseType = ClassName.bestGuess(imp);
                    break;
                }
            }
        }

        // 2. Zweite Priorität: Bereits voll qualifizierter Typname (z. B. com.foo.MyType)
        if (baseType == null && name.contains(".")) {
            baseType = ClassName.bestGuess(name);
        }

        // 3. Dritte Priorität: Standard JDK-Mapping (gewinnt nur, wenn nicht per Import überschrieben)
        if (baseType == null && KNOWN_JDK_TYPES.containsKey(name)) {
            baseType = KNOWN_JDK_TYPES.get(name);
        }

        // 4. Fallback: Typ liegt im selben Package wie die DSL-Datei
        if (baseType == null) {
            baseType = ClassName.get(defaultPackage, name);
        }

        // Generics rekursiv auflösen
        if (!typeNode.typeArguments().isEmpty()) {
            TypeName[] argTypes = typeNode.typeArguments().stream()
                    .map(arg -> resolve(arg, defaultPackage, explicitImports))
                    .toArray(TypeName[]::new);
            return ParameterizedTypeName.get((ClassName) baseType, argTypes);
        }

        return baseType;
    }
}