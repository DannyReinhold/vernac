// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DomainChecksTest {
    @Test void returnsTheSameReferenceOrAFieldQualifiedDomainError() {
        Object value = new Object();
        assertThat(DomainChecks.requireNonNull(value, "Stop.id")).isSameAs(value);
        assertThatThrownBy(() -> DomainChecks.requireNonNull(null, "Stop.id"))
                .isInstanceOf(DomainValidationException.class).hasMessage("Stop.id must not be null");
    }
}
