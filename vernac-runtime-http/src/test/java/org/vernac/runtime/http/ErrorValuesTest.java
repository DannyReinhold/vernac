// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.http;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ErrorValuesTest {
    @Test void absentComparisonsRemainUnknownIncludingInequalityAndNegation() {
        assertEquals(ErrorCondition.UNAVAILABLE, ErrorValues.equal(Optional.empty(), "x", true).not());
        assertEquals(ErrorCondition.UNAVAILABLE, ErrorValues.number(Optional.empty(), "1", ">="));
        assertEquals(ErrorCondition.FALSE, ErrorValues.equal(Optional.of("ABC"), "abc", false));
    }
    @Test void wireNumbersUseExactNumericEqualityAndOrdering() {
        assertTrue(ErrorValues.number(Optional.of(new BigDecimal("1.00")), "1.0", "=").matches());
        assertTrue(ErrorValues.number(Optional.of(9007199254740993L), "9007199254740992", ">").matches());
        assertTrue(ErrorValues.number(Optional.of(-3), "-2.999", "<").matches());
    }
}
