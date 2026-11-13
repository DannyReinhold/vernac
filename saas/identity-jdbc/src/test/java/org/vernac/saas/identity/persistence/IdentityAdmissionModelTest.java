// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.vernac.saas.identity.persistence.domain.*;

import java.util.UUID;

class IdentityAdmissionModelTest {
    @Test
    void countsCannotOverflowAndClockHighWaterNeverMovesBackwards() {
        var digest = IdentityAdmissionDigest.of("a".repeat(64));
        var row =
                IdentityAdmissionKey.create(
                        IdentityAdmissionKind.LOGIN_KEY,
                        digest,
                        IdentityAdmissionWindow.of(-1),
                        IdentityAdmissionCount.of(Long.MAX_VALUE - 1),
                        IdentityAdmissionSecond.of(0));
        assertTrue(row.attempt(-1, 0, Long.MAX_VALUE));
        assertFalse(row.attempt(-2, 0, Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, row.used().amount());
        assertTrue(row.attempt(0, 900, 1));
        assertEquals(1, row.used().amount());
        assertFalse(row.attempt(0, 900, 1));
        var config =
                IdentityAdmissionConfiguration.create(
                        IdentityAdmissionKeyLabel.of("fixture"),
                        digest,
                        IdentityAdmissionSecond.of(0),
                        IdentityAdmissionCursor.of((UUID) null));
        assertEquals(10, config.observe(10));
        assertEquals(10, config.observe(-10));
        assertThrows(IllegalArgumentException.class, () -> new JdbcIdentityAdmission.Rule(0, 900));
        assertThrows(
                IllegalArgumentException.class, () -> new JdbcIdentityAdmission.Rule(1, 86401));
    }
}
