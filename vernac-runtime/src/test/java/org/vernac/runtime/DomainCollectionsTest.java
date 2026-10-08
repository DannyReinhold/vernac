// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DomainCollectionsTest {
    // Different states with equal identities are intentional, not a persistence mistake.
    private record Account(int id, int balance) {
        @Override public boolean equals(Object other) { return other instanceof Account a && id == a.id; }
        @Override public int hashCode() { return id; }
    }

    @Test void duplicatesReturnsAllOriginalOccurrencesNotJustTheLaterOnes() {
        assertEquals(List.of("A", "B", "A", "B"), DomainCollections.duplicates(List.of("A", "B", "A", "C", "B")));
        assertEquals(List.of("A", "B"), List.copyOf(DomainCollections.set(DomainCollections.duplicates(List.of("A", "B", "A", "C", "B")))));
        assertEquals(List.of(), DomainCollections.duplicates(List.of("A", "B")));
        assertEquals(List.of(), DomainCollections.duplicates(List.of()));
        assertEquals(List.of("A", "A", "A"), DomainCollections.duplicates(List.of("A", "A", "A")));
        Account first = new Account(42, 100), second = new Account(42, 200), unique = new Account(7, 50);
        var duplicates = DomainCollections.duplicates(List.of(first, unique, second));
        assertEquals(2, duplicates.size());
        assertSame(first, duplicates.get(0));
        assertSame(second, duplicates.get(1));
        assertSame(first, DomainCollections.set(duplicates).iterator().next());
    }

    @Test void setsRetainExistingInstancesAndFirstNewOccurrences() {
        Account old = new Account(42, 100), newer = new Account(42, 200);
        var source = DomainCollections.set(List.of(old));
        var result = DomainCollections.set(DomainCollections.plusAll(source, List.of(newer, new Account(7, 1), new Account(7, 2))));
        assertEquals(2, result.size());
        assertSame(old, result.iterator().next());
        assertEquals(1, result.stream().filter(a -> a.id == 7).findFirst().orElseThrow().balance);
        assertSame(old, DomainCollections.find(result, newer).orElseThrow());
        assertEquals(1, DomainCollections.count(result, newer));
        assertEquals(source, DomainCollections.set(List.of(newer))); // identity equality, not snapshot equality
    }

    @Test void matchingAndMinusPreserveSourceInstancesOrderAndMultiplicities() {
        var source = List.of("A", "B", "A", "C");
        assertEquals(List.of("B", "A", "C"), DomainCollections.minus(source, "A"));
        assertEquals(List.of("A", "A", "C"), DomainCollections.matching(source, List.of("A", "C", "A"), true));
        assertEquals(List.of("B", "C"), DomainCollections.matching(source, List.of("A", "A"), false));
        assertEquals(2, DomainCollections.count(source, "A"));
        assertEquals(0, DomainCollections.count(source, "absent"));
        assertEquals(source, DomainCollections.minus(source, "absent"));
        assertEquals(List.of("A", "B", "A", "C"), source);
    }

    @Test void inputsAndExposedStructuresAreProtectedAndNullIsRejected() {
        var input = new ArrayList<>(List.of("A", "B"));
        var list = DomainCollections.list(input);
        var set = DomainCollections.set(input);
        input.clear();
        assertEquals(2, list.size());
        assertEquals(2, set.size());
        assertThrows(UnsupportedOperationException.class, () -> list.clear());
        assertThrows(UnsupportedOperationException.class, () -> set.clear());
        var iterator = list.iterator(); iterator.next();
        assertThrows(UnsupportedOperationException.class, iterator::remove);
        assertThrows(DomainValidationException.class, () -> DomainCollections.list(null));
        assertThrows(DomainValidationException.class, () -> DomainCollections.list(Arrays.asList("A", null)));
        assertThrows(DomainValidationException.class, () -> DomainCollections.count(list, null));
        assertThrows(DomainValidationException.class, () -> DomainCollections.matching(list, Arrays.asList((String) null), true));
        assertThrows(DomainValidationException.class, () -> DomainCollections.plusAll(list, Arrays.asList("C", null)));
        assertEquals(List.of("A", "B"), list);
    }
}
