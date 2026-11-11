// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.tenancy;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantIdTest {
    @Test void typedIdentityHasExplicitStorageRepresentationAndSafeDefaultRendering() {
        UUID value = UUID.randomUUID();
        TenantId first = TenantId.of(value);
        TenantId second = TenantId.of(value.toString());
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(value, first.value());
        assertEquals(value.toString(), first.asString());
        assertNotEquals(value, first);
        assertFalse(first.toString().contains(value.toString()));
    }
    @Test void malformedInputIsNotCopiedIntoAnExceptionOrCause() {
        var error = assertThrows(IllegalArgumentException.class, () -> TenantId.of("sensitive-fixture-value"));
        assertFalse(error.toString().contains("sensitive-fixture-value"));
        assertNull(error.getCause());
        assertThrows(NullPointerException.class, () -> TenantId.of((UUID)null));
    }
}
