package org.vernac.runtime.jdbc;

import java.math.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.vernac.runtime.VernacTechnicalException;
import static org.junit.jupiter.api.Assertions.*;

class ScalarStorageLimitsTest {
    @Test void textRejectsLossyRepresentationsButPreservesUnicode() {
        for (String invalid : List.of("secret\0", "\uD800", "\uDC00", "\uD800x")) {
            var error = assertThrows(VernacTechnicalException.class,
                    () -> ScalarCodec.encode("String", invalid));
            assertFalse(error.getMessage().contains("secret"));
        }
        String text = "Grüße 𐐀 e\u0301  ";
        assertEquals(text, ScalarCodec.decode("String", ScalarCodec.encode("String", text)));
        assertEquals('\uD800', ScalarCodec.decode("char", ScalarCodec.encode("char", '\uD800')));
    }

    @Test void decimalBoundsIncludeScaleWithoutExpandingHugeExponents() {
        for (BigDecimal value : List.of(new BigDecimal("1E+131071"), new BigDecimal("1E-16383"),
                new BigDecimal("1.00"), new BigDecimal("0E+131071"))) {
            assertEquals(value, ScalarCodec.decode("BigDecimal", ScalarCodec.encode("BigDecimal", value)));
        }
        for (BigDecimal value : List.of(new BigDecimal("1E+131072"), new BigDecimal("1E-16384"),
                new BigDecimal(BigInteger.ZERO, Integer.MIN_VALUE),
                new BigDecimal(BigInteger.ZERO, Integer.MAX_VALUE))) {
            assertThrows(VernacTechnicalException.class, () -> ScalarCodec.encode("BigDecimal", value));
        }
        assertThrows(VernacTechnicalException.class,
                () -> ScalarCodec.encode("BigInteger", BigInteger.TEN.pow(131072)));
    }

    @Test void timestampLimitsApplyBeforeUtcConversionAndPreserveNanoseconds() {
        for (Instant value : List.of(Instant.parse("0001-01-01T00:00:00Z"),
                Instant.parse("9999-12-31T23:59:59.999999999Z"))) {
            assertEquals(value, ScalarCodec.decode("Instant", ScalarCodec.encode("Instant", value)));
        }
        for (Instant value : List.of(Instant.MIN, Instant.MAX)) {
            assertThrows(VernacTechnicalException.class, () -> ScalarCodec.encode("Instant", value));
        }
        assertThrows(VernacTechnicalException.class,
                () -> ScalarCodec.encode("LocalDate", LocalDate.of(0, 12, 31)));
        assertThrows(VernacTechnicalException.class,
                () -> ScalarCodec.encode("LocalDateTime", LocalDateTime.of(10000, 1, 1, 0, 0)));
        assertThrows(VernacTechnicalException.class,
                () -> ScalarCodec.encode("OffsetDateTime", OffsetDateTime.parse("0001-01-01T00:00:00+01:00")));
        assertThrows(VernacTechnicalException.class,
                () -> ScalarCodec.encode("ZonedDateTime", LocalDateTime.of(0,1,1,0,0).atZone(ZoneOffset.UTC)));
    }

    @Test void mappingAddsColumnContextWithoutLeakingTheValueOrWritingARow() {
        var row = new HashMap<String,Object>();
        JdbcMapping.Writer unused = new JdbcMapping.Writer() {
            public UUID entity(String table, Object value) { throw new AssertionError(); }
            public Map<String,Object> row(String table) { throw new AssertionError(); }
        };
        var error = assertThrows(VernacTechnicalException.class,
                () -> JdbcMapping.scalar("String", false, "address.street")
                        .write("secret\0", row, unused, true));
        assertTrue(error.getMessage().contains("address.street"));
        assertFalse(error.getMessage().contains("secret"));
        assertTrue(row.isEmpty());
    }
}
