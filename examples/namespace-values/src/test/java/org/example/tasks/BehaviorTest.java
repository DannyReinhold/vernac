// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.example.tasks;

import org.example.tasks.domain.PersonName;
import org.example.tasks.domain.PersonNames;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BehaviorTest {
    @Test void behaviorUsesInlineAndExternalImplementations() {
        var name = PersonName.of("Hans");
        assertEquals("HANS!", name.upper());
        assertEquals(4, name.length());
        assertTrue(name.alternative().isEmpty());
        assertTrue(PersonNames.of(name, name).hasDuplicates());
        assertFalse(PersonNames.of(name).hasDuplicates());
        assertEquals("ADA!", PersonName.of("Ada").upper());
        assertThrows(org.vernac.runtime.DomainValidationException.class,
                () -> PersonName.of(" "));
    }
}
