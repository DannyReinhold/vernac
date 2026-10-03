package org.vernac.compiler.generator;

import com.palantir.javapoet.TypeName;

public record FlatColumn(
        String columnName,          // z. B. "money_amount" oder "capacity"
        String sqlParameterName,     // z. B. "moneyAmount" oder "capacity"
        String propertyPath,         // z. B. "aggregate.money().amount()"
        String postgresType,         // z. B. "NUMERIC(19, 4)" oder "INTEGER"
        TypeName boxedJavaType,      // z. B. BigDecimal oder Integer (für rs.getObject)
        boolean isOptional
) {
}