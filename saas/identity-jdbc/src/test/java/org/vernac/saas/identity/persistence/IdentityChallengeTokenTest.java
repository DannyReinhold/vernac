// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class IdentityChallengeTokenTest {
    @Test
    void tokensAreCanonicalRandomAndRedacted() {
        var seen = new HashSet<String>();
        for (int i = 0; i < 1000; i++) {
            var token = IdentityChallengeToken.create();
            String transport = token.deliveryValue();
            assertEquals(80, transport.length());
            assertTrue(seen.add(transport));
            assertEquals(4, token.locator().version());
            var parsed = IdentityChallengeToken.parse(transport);
            assertNotNull(parsed);
            assertEquals(token.locator(), parsed.locator());
            assertTrue(parsed.matches(token.verifier()));
            assertFalse(IdentityChallengeToken.create().matches(token.verifier()));
            assertFalse(token.toString().contains(transport.substring(37)));
            assertFalse(token.toString().contains(token.verifier()));
        }
    }

    @Test
    void malformedAndNoncanonicalInputsAreRejectedWithoutSecretErrors() {
        for (String input :
                List.of(
                        "",
                        "a".repeat(1_000_000),
                        UUID.randomUUID().toString(),
                        "00000000-0000-0000-0000-000000000000." + "A".repeat(43)))
            assertNull(IdentityChallengeToken.parse(input));
        assertNull(IdentityChallengeToken.parse(null));
        var token = IdentityChallengeToken.create();
        String input = token.deliveryValue();
        assertNull(IdentityChallengeToken.parse(input + "="));
        assertNull(IdentityChallengeToken.parse(input.substring(0, 37) + "A".repeat(42) + "B"));
        var failure =
                assertThrows(IllegalStateException.class, () -> token.matches("seeded-secret"));
        assertFalse(failure.toString().contains("seeded-secret"));
    }
}
