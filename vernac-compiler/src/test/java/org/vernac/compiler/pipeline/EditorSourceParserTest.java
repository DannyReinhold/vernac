// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.analyzer.*;
import org.vernac.compiler.tooling.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

class EditorSourceParserTest {
    @TempDir Path root;
    private static final String PREFIX = "namespace model;\nvalue Count(int);\n";

    private record Loaded(
            VernacProject project, List<CompilerDiagnostic> diagnostics, Path path, String text) {}

    private Loaded load(String text) throws Exception {
        Path file = root.resolve("model/main.vernac");
        Files.createDirectories(file.getParent());
        var diagnostics = new ArrayList<CompilerDiagnostic>();
        var project =
                new VernacProjectLoader().loadForAnalysis(root, Map.of(file, text), diagnostics);
        return new Loaded(project, diagnostics, file, text);
    }

    private Set<String> names(Loaded loaded) {
        var names = new HashSet<String>();
        loaded.project()
                .symbols()
                .inNamespace("model")
                .forEach(s -> names.add(s.identity().name()));
        return names;
    }

    @Test
    void retainsCompleteTypesBeforeAnUnclosedBehavior() throws Exception {
        var loaded = load(PREFIX + "usecase Broken(Count) behavior { execute { self.");
        assertEquals(Set.of("Count"), names(loaded));
        assertFalse(loaded.diagnostics().isEmpty());
    }

    @Test
    void retainsExistingJavaContextsAndNavigationInTheSameDamagedFile() throws Exception {
        String text =
                """
                namespace model;
                // 🧭 Unicode offsets stay source based.
                term Count {description "A counter.";}
                value Count(int) behavior {
                    public int doubled() { return self.intValue() * 2; }
                };
                value Other(Count);
                value Broken(;
                """;
        var loaded = load(text);
        var source = loaded.project().sources().getFirst();
        var blocks = new BehaviorJavaProjection().build(loaded.project(), source, text);
        assertEquals(1, blocks.size());
        assertTrue(
                blocks.getFirst().fragments().stream()
                        .anyMatch(
                                fragment ->
                                        text.substring(fragment.start(), fragment.end())
                                                .contains("self.intValue()")));
        var target =
                new VernacTypeNavigation()
                        .find(loaded.project(), Map.of(loaded.path(), text), "model.domain.Count")
                        .orElseThrow();
        assertEquals("Count", text.substring(target.start(), target.end()));
        var refs =
                new VernacTypeReferences()
                        .build(loaded.project(), loaded.path(), Map.of(loaded.path(), text));
        assertTrue(
                refs.stream()
                        .anyMatch(
                                r ->
                                        r.identity().equals("model.Count")
                                                && r.start() == text.indexOf("Count);")));
        assertTrue(
                new VernacTypeReferences()
                        .javaReferences(text).stream()
                                .noneMatch(r -> r.start() >= text.indexOf("value Broken")));
    }

    @Test
    void doesNotInventMissingParameterTypesOrNames() throws Exception {
        var loaded = load(PREFIX + "value Broken(String title, );");
        assertFalse(loaded.diagnostics().isEmpty());
        assertEquals(Set.of("Count"), names(loaded));
    }

    @Test
    void aForbiddenNameInvalidatesOnlyItsDeclaration() throws Exception {
        var loaded = load(PREFIX + "value Broken(String na\u200Bme); value Healthy(int);");
        assertFalse(loaded.diagnostics().isEmpty());
        assertTrue(names(loaded).containsAll(Set.of("Count", "Healthy")));
        assertFalse(names(loaded).contains("Broken"));
    }

    @Test
    void balancedIncompleteJavaKeepsItsOwnerAndInjection() throws Exception {
        var loaded =
                load(
                        PREFIX
                                + "usecase Demo(Count) returns Count behavior {execute {return"
                                + " count.;}}");
        assertTrue(loaded.diagnostics().isEmpty());
        assertTrue(names(loaded).contains("Demo"));
        assertEquals(
                1,
                new BehaviorJavaProjection()
                        .build(
                                loaded.project(),
                                loaded.project().sources().getFirst(),
                                loaded.text())
                        .size());
    }

    @Test
    void semanticErrorsElsewhereDoNotEraseRecognizableDeclarations() throws Exception {
        var loaded = load(PREFIX + "value Broken(Unknown);");
        assertTrue(names(loaded).contains("Count"));
        assertThrows(
                SemanticValidationException.class,
                () -> new ProjectTypeResolver().resolve(loaded.project()));
    }

    @Test
    void strictBuildStillRejectsTheDamagedFile() throws Exception {
        String text = PREFIX + "value Broken(;";
        var loaded = load(text);
        assertTrue(names(loaded).contains("Count"));
        Files.writeString(loaded.path(), text);
        assertThrows(SemanticValidationException.class, () -> new VernacProjectLoader().load(root));
        assertThrows(
                SemanticValidationException.class, () -> new VernacCompiler().compileProject(root));
        assertThrows(
                SemanticValidationException.class, () -> new VernacCompiler().compileSource(text));
    }

    @Test
    void unsavedRenamesDoNotLeaveLastGoodGhostTypes() throws Exception {
        assertTrue(names(load(PREFIX + "value Broken(;")).contains("Count"));
        var renamed = load(PREFIX.replace("Count", "Total") + "value Broken(;");
        assertEquals(Set.of("Total"), names(renamed));
    }

    @Test
    void invalidHeaderIsNotGuessedAndShortEditsDoNotCrash() {
        for (String text :
                List.of(
                        "",
                        "n",
                        "namespace",
                        "namespace model",
                        "namespace ; value Count(int);",
                        "namespace model; import ; value Count(int);")) {
            var diagnostics = new ArrayList<CompilerDiagnostic>();
            assertTrue(
                    new EditorSourceParser().analyze("edit.vernac", text, diagnostics).isEmpty(),
                    text);
            assertFalse(diagnostics.isEmpty(), text);
        }
    }

    @Test
    void damagedSiblingFileDoesNotRemoveCurrentTypes() throws Exception {
        Path sibling = root.resolve("model/broken.vernac");
        Files.createDirectories(sibling.getParent());
        Files.writeString(sibling, "namespace model; value Damaged(;");
        var loaded = load(PREFIX);
        assertEquals(Set.of("Count"), names(loaded));
        assertFalse(loaded.diagnostics().isEmpty());
    }

    @Test
    void smallDeletionsAroundDeclarationsAndBodiesDoNotCrashRecovery() {
        String original =
                "namespace model; import other.Name; term Count {description \"A counter\";} id"
                    + " CountId; value Count(int) behavior {public int doubled(){return"
                    + " self.intValue()*2;}}; aggregate Model[CountId](mut Count) behavior {modify"
                    + " void change(Count count){self.count(count);}}; usecase Demo(Count) returns"
                    + " Count behavior {execute {return count;}}";
        for (int start = 0; start < original.length(); start++) {
            for (int length = 1; length <= Math.min(5, original.length() - start); length++) {
                String edit = original.substring(0, start) + original.substring(start + length);
                assertDoesNotThrow(
                        () ->
                                new EditorSourceParser()
                                        .analyze("edit.vernac", edit, new ArrayList<>()),
                        edit);
            }
        }
    }
}
