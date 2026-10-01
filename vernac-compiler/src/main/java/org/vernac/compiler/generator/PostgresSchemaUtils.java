package org.vernac.compiler.generator;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.TypeName;
import org.vernac.compiler.ast.FieldNode;
import org.vernac.compiler.ast.TypeNode;
import org.vernac.compiler.ast.ValueObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.*;

public final class PostgresSchemaUtils {

    private PostgresSchemaUtils() {
    }

    public static String toSnakeCase(String camel) {
        if (camel == null || camel.isBlank()) return "";
        return camel.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase(Locale.ROOT);
    }

    public static String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    /**
     * Mappt AST-Typen auf PostgreSQL-Spaltentypen.
     */
    public static String mapToPostgresType(TypeNode typeNode) {
        String name = typeNode.name();
        return switch (name) {
            case "int", "Integer" -> "INTEGER";
            case "long", "Long" -> "BIGINT";
            case "short", "Short" -> "SMALLINT";
            case "double", "Double" -> "DOUBLE PRECISION";
            case "float", "Float" -> "REAL";
            case "boolean", "Boolean" -> "BOOLEAN";
            case "BigDecimal" -> "NUMERIC(19, 4)";
            case "Instant", "LocalDateTime", "ZonedDateTime" -> "TIMESTAMPTZ";
            case "LocalDate" -> "DATE";
            case "UUID" -> "UUID";
            case "String" -> "VARCHAR(255)";
            default -> "TEXT";
        };
    }

    /**
     * Garantiert immer Object-Wrapper für ResultSet.getObject(..., Class).
     */
    public static TypeName resolveBoxedType(TypeNode typeNode, String targetPackage) {
        String name = typeNode.name();
        return switch (name) {
            case "int" -> ClassName.get(Integer.class);
            case "long" -> ClassName.get(Long.class);
            case "short" -> ClassName.get(Short.class);
            case "double" -> ClassName.get(Double.class);
            case "float" -> ClassName.get(Float.class);
            case "boolean" -> ClassName.get(Boolean.class);
            case "BigDecimal" -> ClassName.get(BigDecimal.class);
            case "Instant" -> ClassName.get(Instant.class);
            case "LocalDate" -> ClassName.get(LocalDate.class);
            case "LocalDateTime" -> ClassName.get(LocalDateTime.class);
            case "ZonedDateTime" -> ClassName.get(ZonedDateTime.class);
            case "UUID" -> ClassName.get(UUID.class);
            case "String" -> ClassName.get(String.class);
            default -> TypeResolver.resolve(typeNode, targetPackage);
        };
    }

    /**
     * Öffentlicher Einstiegspunkt zum Flattening eines Aggregat- oder Entity-Feldes.
     */
    public static List<FlatColumn> flattenField(
            FieldNode field,
            String baseAccessor,
            Map<String, ValueObjectNode> valueObjects,
            String targetPackage
    ) {
        return flattenRecursive(
                resolveColumnName(field.name()),
                field.name(),
                baseAccessor + "." + field.name() + "()",
                field.type(),
                field.type().isOptional(),
                valueObjects,
                targetPackage
        );
    }

    private static List<FlatColumn> flattenRecursive(
            String colPrefix,
            String paramPrefix,
            String accessorPath,
            TypeNode type,
            boolean isParentOptional,
            Map<String, ValueObjectNode> valueObjects,
            String targetPackage
    ) {
        List<FlatColumn> result = new ArrayList<>();
        String typeName = type.name();
        boolean effectivelyOptional = isParentOptional || type.isOptional();

        if (valueObjects.containsKey(typeName)) {
            ValueObjectNode vo = valueObjects.get(typeName);

            if (vo.fields().size() == 1) {
                // Single Value Object: Kein Namenszusatz
                FieldNode inner = vo.fields().getFirst();
                String innerAccessor = accessorPath + "." + (inner.name().equals("value") ? "value()" : inner.name() + "()");
                result.addAll(flattenRecursive(colPrefix, paramPrefix, innerAccessor, inner.type(), effectivelyOptional, valueObjects, targetPackage));
            } else {
                // Multi Value Object: Präfix_Feldname
                for (FieldNode inner : vo.fields()) {
                    String subCol = colPrefix + "_" + resolveColumnName(inner.name());
                    String subParam = paramPrefix + capitalize(inner.name());
                    String innerAccessor = accessorPath + "." + inner.name() + "()";
                    result.addAll(flattenRecursive(subCol, subParam, innerAccessor, inner.type(), effectivelyOptional, valueObjects, targetPackage));
                }
            }
        } else {
            // Leaf-Typ (primitiv oder Basistyp)
            TypeName boxedType = resolveBoxedType(type, targetPackage);
            String pgType = mapToPostgresType(type);
            result.add(new FlatColumn(colPrefix, paramPrefix, accessorPath, pgType, boxedType, effectivelyOptional));
        }

        return result;
    }

    /**
     * Erzeugt das DDL anhand der tatsächlichen, geflachten Spalten.
     */
    public static String generateAggregateDdl(String tableName, String idColumn, List<FieldNode> fields, Map<String, ValueObjectNode> valueObjects, String targetPackage) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS ").append(tableName).append(" (\n");
        sb.append("    ").append(idColumn).append(" UUID PRIMARY KEY,\n");

        for (FieldNode field : fields) {
            if (field.type().name().equals("List")) continue;
            List<FlatColumn> flatCols = flattenField(field, "", valueObjects, targetPackage);
            for (FlatColumn col : flatCols) {
                String nullable = col.isOptional() ? "" : " NOT NULL";
                sb.append("    ").append(col.columnName()).append(" ").append(col.postgresType()).append(nullable).append(",\n");
            }
        }

        sb.append("    created_at TIMESTAMPTZ NOT NULL,\n");
        sb.append("    updated_at TIMESTAMPTZ NOT NULL,\n");
        sb.append("    version BIGINT NOT NULL DEFAULT 0\n");
        sb.append(");");
        return sb.toString();
    }

    public static String generateEntityDdl(String tableName, String idColumn, List<FieldNode> fields, Map<String, ValueObjectNode> valueObjects, String targetPackage) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS ").append(tableName).append(" (\n");
        sb.append("    ").append(idColumn).append(" UUID PRIMARY KEY");

        for (FieldNode field : fields) {
            if (field.type().name().equals("List")) continue;
            List<FlatColumn> flatCols = flattenField(field, "", valueObjects, targetPackage);
            for (FlatColumn col : flatCols) {
                String nullable = col.isOptional() ? "" : " NOT NULL";
                sb.append(",\n    ").append(col.columnName()).append(" ").append(col.postgresType()).append(nullable);
            }
        }

        sb.append("\n);");
        return sb.toString();
    }

    public static String resolveTableName(String typeName) {
        return toSnakeCase(typeName);
    }

    /**
     * Ermittelt den einheitlichen Spaltennamen für ein Feld.
     * Konvention: Strikter Spaltenname in snake_case.
     */
    public static String resolveColumnName(String fieldName) {
        return toSnakeCase(fieldName);
    }

    /**
     * Ermittelt den Namen der Fremdschlüssel-Spalte, die auf ein Aggregat verweist.
     * Konvention: <aggregate_singular_snake_case>_id (z. B. "energy_storage_id").
     */
    public static String resolveForeignKeyColumn(String aggregateName) {
        return toSnakeCase(aggregateName) + "_id";
    }
}