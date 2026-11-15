// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.roles;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class RoleAssignmentsTest {
    @Test void representationIsFiniteCanonicalAndRoundTripsEmptyAndMultipleRoles() {
        assertEquals("",RoleAssignments.encode(List.of()));assertTrue(RoleAssignments.decode("").isEmpty());
        assertEquals("example.editor,example.reader",RoleAssignments.encode(List.of("example.reader","example.editor")));
        assertEquals(Set.of("example.reader","example.editor"),RoleAssignments.decode("example.editor,example.reader"));
    }
    @Test void duplicatesUnknownGrammarNoncanonicalAndExcessiveInputAreRejected() {
        for(String text:List.of("example.reader,example.reader","example.reader,example.editor","*","example.reader,","example.owner, root"))
            assertThrows(IllegalArgumentException.class,()->RoleAssignments.decode(text));
        assertThrows(IllegalArgumentException.class,()->RoleAssignments.encode(Collections.nCopies(33,"example.reader")));
    }
}
