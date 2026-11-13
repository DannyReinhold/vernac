// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.vernac.saas.identity.persistence.domain.*;

import java.util.List;

class IdentityLoginAddressModelTest {
    @Test
    void canonicalAndDeliveryValuesReuseTheExistingAddressPolicyAndRedactRepresentations() {
        String original = "Alice+Reports@Example.COM", key = "alice+reports@example.com";
        var canonical = IdentityLoginKey.of(key);
        var delivery = IdentityDeliveryAddress.of(original);
        var owned = IdentityLoginAddress.create(canonical, delivery);
        assertEquals(key, owned.loginKey().encoded());
        assertEquals(original, owned.deliveryAddress().encoded());
        for (Object value : List.of(canonical, delivery, owned)) {
            assertFalse(value.toString().contains(key));
            assertFalse(value.toString().contains(original));
            assertTrue(value.toString().contains("REDACTED"));
        }
        for (String invalid :
                List.of(
                        original,
                        " " + key,
                        "alice@bücher.example",
                        "alice@example",
                        "Alice <alice@example.com>"))
            assertThrows(RuntimeException.class, () -> IdentityLoginKey.of(invalid));
        assertThrows(RuntimeException.class, () -> IdentityDeliveryAddress.of(" " + original));
        assertThrows(
                RuntimeException.class,
                () ->
                        IdentityLoginAddress.create(
                                canonical, IdentityDeliveryAddress.of("another@example.com")));
        assertNotEquals(canonical, IdentityLoginKey.of("alice@example.com"));
        assertNotEquals(canonical, IdentityLoginKey.of("a.lice+reports@example.com"));
    }
}
