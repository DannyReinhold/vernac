// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.vernac.runtime.DomainValidationException;
import org.vernac.saas.identity.domain.IdentityStatus;
import org.vernac.saas.identity.persistence.domain.*;

class IdentitySecurityEpochTest {
    @Test
    void revocationEpochIsMonotonicAndCannotOverflowOrWrap() {
        var identity =
                Identity.create(IdentityStatus.PENDING_VERIFICATION, IdentitySecurityEpoch.of(0));
        identity.activate();
        identity.credentialChanged();
        assertEquals(1, identity.securityEpoch().number());
        identity.disable();
        assertEquals(2, identity.securityEpoch().number());
        identity.close();
        identity.close();
        assertEquals(3, identity.securityEpoch().number());
        assertThrows(DomainValidationException.class, () -> IdentitySecurityEpoch.of(-1));
        var exhausted =
                Identity.create(IdentityStatus.ACTIVE, IdentitySecurityEpoch.of(Long.MAX_VALUE));
        assertThrows(DomainValidationException.class, exhausted::credentialChanged);
        assertThrows(DomainValidationException.class, exhausted::disable);
        assertThrows(DomainValidationException.class, exhausted::close);
        assertEquals(Long.MAX_VALUE, exhausted.securityEpoch().number());
        assertEquals(IdentityStatus.ACTIVE, exhausted.status());
    }
}
