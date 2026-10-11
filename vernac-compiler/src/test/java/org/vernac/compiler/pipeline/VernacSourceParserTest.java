// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.junit.jupiter.api.Test;
import org.vernac.compiler.analyzer.SemanticValidationException;
import static org.junit.jupiter.api.Assertions.*;

class VernacSourceParserTest {
    private final VernacSourceParser parser = new VernacSourceParser();

    @Test
    void requiresNamespaceAndRejectsTheOldFileLevelPackageSyntax() {
        for (String source : new String[]{"id TaskId;", "package org.example; id TaskId;", ""}) {
            var error = assertThrows(SemanticValidationException.class, () -> parser.parse("model.vernac", source));
            assertTrue(error.getMessage().contains("namespace"));
            assertEquals("model.vernac", error.diagnostics().getFirst().location().sourceName());
        }
    }

    @Test
    void propagatesSourceNameAndOneBasedCoordinatesIntoNestedNodes() {
        var unit = parser.parse("model.vernac", "namespace org.example;\nvalue Title(String value);");
        assertEquals("org.example", unit.namespace());
        assertEquals("model.vernac", unit.location().sourceName());
        var value = unit.valueObjects().getFirst();
        assertEquals(2, value.location().line());
        assertEquals(1, value.location().column());
        assertEquals("model.vernac", value.fields().getFirst().type().location().sourceName());
        assertEquals(13, value.fields().getFirst().type().location().column());
    }

    @Test
    void reportsSyntaxErrorColumnAsOneBased() {
        var error = assertThrows(SemanticValidationException.class,
                () -> parser.parse("broken.vernac", "namespace org.example;\n  id ;"));
        var location = error.diagnostics().getFirst().location();
        assertEquals("broken.vernac", location.sourceName());
        assertEquals(2, location.line());
        assertEquals(6, location.column());
    }

    @Test
    void handlesKeywordSegmentsInImportsAndQualifiedTypeReferences() {
        var unit = parser.parse("model.vernac", """
                namespace org.example;
                import org.custom.value.Title;
                value Label(org.custom.value.Title title);
                """);
        assertEquals("org.custom.value.Title", unit.imports().getFirst().text());
        assertEquals("org.custom.value.Title", unit.valueObjects().getFirst().fields().getFirst().type().name());
    }
}
