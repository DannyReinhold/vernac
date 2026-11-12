// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.vernac.saas.identity.persistence.domain.*;

class IdentityIngressModelTest {
    @Test
    void maximumCounterDoesNotOverflowAndClockRegressionDoesNotReset() {
        var row =
                IdentityIngress.create(
                        IdentityIngressMinute.of(-1),
                        IdentityIngressCount.of(Long.MAX_VALUE - 1),
                        IdentityIngressCount.of(Long.MAX_VALUE));
        assertTrue(row.attempt(-1));
        assertFalse(row.attempt(-2));
        assertEquals(Long.MAX_VALUE, row.used().amount());
        row.changeLimit(1);
        assertFalse(row.attempt(-1));
        assertTrue(row.attempt(0));
        assertEquals(1, row.used().amount());
        assertFalse(row.attempt(0));
        assertEquals(0, row.minute().number());
        assertThrows(org.vernac.runtime.DomainValidationException.class, () -> row.changeLimit(0));
    }
}
