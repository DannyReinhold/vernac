// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.symbols;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class BuiltinTypesTest {
    @Test
    void catalogMatchesTheApprovedFieldTypes() {
        assertEquals(Set.of(
                "boolean", "byte", "short", "int", "long", "float", "double", "char",
                "Boolean", "Byte", "Short", "Integer", "Long", "Float", "Double", "Character",
                "String", "BigInteger", "BigDecimal", "UUID", "Instant", "LocalDate", "LocalTime",
                "LocalDateTime", "OffsetDateTime", "OffsetTime", "ZonedDateTime", "Duration",
                "Period", "Year", "YearMonth", "MonthDay", "Month", "DayOfWeek", "ZoneId",
                "ZoneOffset", "Currency"), BuiltinTypes.names());
        assertEquals(int.class, BuiltinTypes.find("int").orElseThrow());
        assertEquals(Integer.class, BuiltinTypes.find("Integer").orElseThrow());
        assertEquals(java.util.Currency.class, BuiltinTypes.find("Currency").orElseThrow());
    }

    @Test
    void doesNotResolveArbitraryJavaNamesOrUnsupportedFieldTypes() {
        for (String name : Set.of("Object", "Number", "CharSequence", "Date", "Calendar",
                "StringBuilder", "List", "Set", "Map", "Optional", "void", "Void",
                "String[]", "java.lang.String", "org.example.MyJavaClass", "currency")) {
            assertTrue(BuiltinTypes.find(name).isEmpty(), name);
        }
        assertThrows(UnsupportedOperationException.class, () -> BuiltinTypes.names().remove("String"));
    }
}
