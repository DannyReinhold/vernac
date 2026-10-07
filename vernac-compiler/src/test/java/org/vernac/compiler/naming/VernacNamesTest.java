// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.naming;

import org.junit.jupiter.api.Test;
import org.vernac.language.VernacNames;
import static org.junit.jupiter.api.Assertions.*;

class VernacNamesTest {
    @Test void acceptsUnicode15JavaIdentifiersAndRejectsLaterAdditions() {
        for (String name : new String[]{"Größe", "Straße", "注文", "𐐀name", "o\u0308", "$name", "_name", "\uD807\uDF02"})
            assertTrue(VernacNames.isIdentifier(name), name);
        // Cyrillic Tje was introduced in Unicode 16, after the Java 21 target.
        assertFalse(VernacNames.isIdentifier("\u1C89"));
        for (String name : new String[]{"", "_", "class", "true", "null", "1name", "\u0308name", "A\u200BB", "A\u0000B", "A\u202EB", "A\u200DB", "A\uD800B"})
            assertFalse(VernacNames.isIdentifier(name), name);
        assertTrue(VernacNames.isNamespace("org.custom.aufträge"));
        assertTrue(VernacNames.isNamespace("org.record"));
        assertFalse(VernacNames.isNamespace("org.class"));
        assertFalse(VernacNames.isTypeName("record"));
    }

    @Test void matchesTheCompleteJava21AlphabetWhenRunningOnJava21() {
        if (Runtime.version().feature() != 21) return;
        for (int point = 0; point <= Character.MAX_CODE_POINT; point++) {
            boolean excluded = Character.isIdentifierIgnorable(point)
                    || Character.getType(point) == Character.CONTROL || Character.getType(point) == Character.FORMAT;
            assertEquals(Character.isJavaIdentifierStart(point) && !excluded, VernacNames.isStart(point), "start " + point);
            assertEquals(Character.isJavaIdentifierPart(point) && !excluded, VernacNames.isPart(point), "part " + point);
        }
    }
}
