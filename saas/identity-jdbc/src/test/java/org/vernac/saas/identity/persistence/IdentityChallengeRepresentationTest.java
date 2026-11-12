// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.vernac.saas.identity.domain.UserId;
import org.vernac.saas.identity.persistence.domain.*;

import java.time.Instant;
import java.util.Map;

class IdentityChallengeRepresentationTest {
    @Test
    void generatedVerifierAndContainingChallengeDoNotRenderTheStoredHash() {
        var token = IdentityChallengeToken.create();
        String hash = token.verifier();
        var verifier = IdentityChallengeVerifier.of(hash);
        assertEquals(hash, verifier.encoded());
        assertTrue(token.matches(verifier.encoded()));
        assertEquals("IdentityChallengeVerifier[REDACTED]", verifier.toString());
        var row =
                IdentityChallenge.create(
                        UserId.create(),
                        IdentityChallengePurpose.VERIFICATION,
                        verifier,
                        IdentityChallengeExpiry.of(Instant.now().plusSeconds(60)));
        for (var value : java.util.List.of(row, Map.of("challenge", row))) {
            assertFalse(value.toString().contains(hash));
            assertFalse(value.toString().contains(token.deliveryValue()));
            assertTrue(value.toString().contains("IdentityChallengeVerifier[REDACTED]"));
        }
    }
}
