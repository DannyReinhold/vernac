// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.testutil.InMemoryJavaCompiler;
import java.nio.file.*;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.*;

class UnicodeCompilationTest {
    @TempDir Path root;
    private void source(String namespace, String name, String body) throws Exception {
        Path file = root.resolve(namespace.replace('.', '/')).resolve(name + ".vernac");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "namespace " + namespace + ";\n" + body);
    }

    @Test void compilesUnicodeAcrossFilesNamespacesAndJavaFactories() throws Exception {
        source("de.aufträge", "namen", "id AuftragId; value Größe(String text); value 𐐀name(String text);");
        source("de.aufträge", "titel", "value Titel(Größe größe, 𐐀name 𐐨name);");
        source("例.注文", "model", """
                import de.aufträge.Titel;
                import de.aufträge.AuftragId;
                value Bestellung(AuftragId id, Titel titel) behavior {
                    public String beschreibung() { return self.titel().größe().text(); }
                }
                value Zustand = GEÖFFNET | 完了;
                """);
        var result = new VernacCompiler().compileProject(root);
        var java = InMemoryJavaCompiler.compile(result.generatedFiles());
        assertTrue(java.success(), java.diagnostics().toString());
        var size = java.loadClass("de.aufträge.domain.Größe");
        var name = java.loadClass("de.aufträge.domain.𐐀name");
        Object sizeValue = size.getMethod("of", String.class).invoke(null, "Grüße 😀");
        Object nameValue = name.getMethod("of", String.class).invoke(null, "Name");
        var title = java.loadClass("de.aufträge.domain.Titel");
        Object instance = title.getMethod("of", size, name).invoke(null, sizeValue, nameValue);
        assertEquals(sizeValue, title.getMethod("größe").invoke(instance));
        assertEquals(nameValue, title.getMethod("𐐨name").invoke(instance));
        Path output = root.resolve("output");
        result.writeTo(output);
        assertTrue(Files.readString(output.resolve("de/aufträge/domain/Titel.java")).contains("𐐨name"));
    }

    @Test void retainsDistinctNormalizationAndCaseSpellings() throws Exception {
        source("org.test", "values", "value Größe(String value); value Gro\u0308ße(String value);");
        source("org.test", "pair", "value Pair(Größe composed, Gro\u0308ße decomposed);");
        var project = new VernacCompiler().compileProject(root);
        assertEquals(3, project.generatedFiles().size());
        var java = InMemoryJavaCompiler.compile(project.generatedFiles());
        assertTrue(java.success(), java.diagnostics().toString());
        assertNotEquals(java.loadClass("org.test.domain.Größe"), java.loadClass("org.test.domain.Gro\u0308ße"));
    }

    @Test void derivesNamesByCodePointWithoutDefaultLocaleDependence() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            var unit = new VernacSourceParser().parse("unicode.vernac", """
                    namespace org.test;
                    value 𐐀name(String value);
                    value Größe(String value);
                    value Pair(𐐀name, Größe);
                    """);
            assertEquals("𐐨name", unit.valueObjects().getLast().fields().getFirst().name());
            assertEquals("größe", unit.valueObjects().getLast().fields().getLast().name());
        } finally { Locale.setDefault(previous); }
    }

    @Test void rejectsInvisibleCharactersWithCodePointDiagnostics() {
        for (String body : new String[]{"id Na\u200Bme;", "value V(String na\u0000me);", "value V = A\u202EB;",
                "value V(String value) behavior { public String na\u200Dme() { return value; } }"}) {
            var error = assertThrows(SemanticValidationException.class,
                    () -> new VernacCompiler().compileSource("namespace org.test; " + body));
            assertTrue(error.getMessage().contains("U+"), error.getMessage());
        }
        assertDoesNotThrow(() -> new VernacSourceParser().parse("test.vernac",
                "namespace org.test; /* \u200B */ value V(String value) validates { require(!self.value().isBlank(), \"\u200B\"); };"));
    }
}
