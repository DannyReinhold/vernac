// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.ports;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PatchFieldTest {
    @Test void threeStatesStayDistinctAndMappingPreservesIntent() {
        assertNotEquals(PatchField.unchanged(),PatchField.clear());
        assertEquals(PatchField.clear(),PatchField.<String>clear().map(String::length));
        assertEquals(PatchField.unchanged(),PatchField.<String>unchanged().map(String::length));
        assertEquals(PatchField.set(3),PatchField.set("abc").map(String::length));
        assertThrows(NullPointerException.class,()->PatchField.set(null));
        assertThrows(NullPointerException.class,()->PatchField.set("x").map(s->null));
        assertTrue(PatchField.clear().value().isEmpty());
    }
}
