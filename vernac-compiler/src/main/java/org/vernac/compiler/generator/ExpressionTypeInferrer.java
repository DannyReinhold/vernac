package org.vernac.compiler.generator;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeName;
import org.vernac.compiler.ast.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ExpressionTypeInferrer {

    private ExpressionTypeInferrer() {
        // Utility class
    }

    public static TypeName infer(
            String expression,
            UseCaseNode useCase,
            Map<String, AggregateNode> aggregates,
            String domainPackage,
            List<String> explicitImports
    ) {
        if (expression == null || expression.isBlank()) {
            return TypeName.get(Object.class);
        }
        String expr = expression.trim();

        // 1. Parameter des UseCases prüfen (z. B. "reason", "id")
        for (FieldNode param : useCase.parameters()) {
            if (param.name().equals(expr)) {
                return TypeResolver.resolve(param.type(), domainPackage, explicitImports);
            }
        }

        // 2. Methoden- oder Property-Aufrufe (z. B. "order.id()", "order.status()")
        if (expr.contains(".") && expr.endsWith("()")) {
            int dot = expr.indexOf('.');
            String varName = expr.substring(0, dot);
            String methodName = expr.substring(dot + 1, expr.length() - 2);

            Optional<String> aggTypeName = findAggregateTypeForVariable(varName, useCase);
            if (aggTypeName.isPresent() && aggregates.containsKey(aggTypeName.get())) {
                AggregateNode agg = aggregates.get(aggTypeName.get());

                // Ist es die ID? (.id() oder benanntes ID-Feld)
                if (methodName.equals("id") || methodName.equals(agg.idDefinition().fieldName())) {
                    return TypeResolver.resolve(agg.idDefinition().type(), domainPackage, explicitImports);
                }

                // Ist es ein Feld-Getter? (.status())
                for (FieldNode f : agg.fields()) {
                    if (f.name().equals(methodName)) {
                        return TypeResolver.resolve(f.type(), domainPackage, explicitImports);
                    }
                }
            }
        }

        // 3. Literale
        if (expr.startsWith("\"") && expr.endsWith("\"")) return ClassName.get(String.class);
        if (expr.matches("-?\\d+")) return TypeName.INT;
        if (expr.matches("-?\\d+\\.\\d+")) return TypeName.DOUBLE;
        if (expr.matches("true|false")) return TypeName.BOOLEAN;

        // Fallback falls Typ zur Compile-Zeit noch nicht exakt auflösbar
        return TypeName.get(Object.class);
    }

    private static Optional<String> findAggregateTypeForVariable(String varName, UseCaseNode useCase) {
        for (UseCaseStatementNode stmt : useCase.statements()) {
            if (stmt instanceof LoadStatementNode load) {
                String instance = load.instanceName().orElseGet(() -> deriveDefaultInstanceName(load.aggregateType()));
                if (instance.equals(varName)) {
                    return Optional.of(load.aggregateType());
                }
            }
        }
        return Optional.empty();
    }

    private static String deriveDefaultInstanceName(String typeName) {
        if (typeName == null || typeName.isEmpty()) return "value";
        return Character.toLowerCase(typeName.charAt(0)) + typeName.substring(1);
    }
}
