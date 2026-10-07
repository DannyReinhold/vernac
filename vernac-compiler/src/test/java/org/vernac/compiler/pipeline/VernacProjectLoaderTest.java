// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.symbols.TypeSymbol;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class VernacProjectLoaderTest {
    @TempDir Path root;
    private final VernacCompiler compiler = new VernacCompiler();

    private Path write(String relativePath, String source) throws IOException {
        Path path = root.resolve(relativePath);
        Files.createDirectories(path.getParent());
        return Files.writeString(path, source);
    }

    @Test
    void indexesAllFilesBeforeResolvingAnyReferences() throws IOException {
        write("org/example/tasks/a-model.vernac", """
                namespace org.example.tasks;
                value Task(Title title, TaskId id);
                """);
        write("org/example/tasks/z-values.vernac", """
                namespace org.example.tasks;
                id TaskId;
                value Title(String value);
                """);
        var project = compiler.readProject(root);
        assertEquals(2, project.sources().size());
        assertEquals(3, project.symbols().inNamespace("org.example.tasks").size());
        assertEquals(TypeSymbol.Kind.ID, project.symbols().find("org.example.tasks.TaskId").orElseThrow().kind());
        assertEquals(TypeSymbol.Kind.VALUE_OBJECT, project.symbols().find("org.example.tasks.Task").orElseThrow().kind());
    }

    @Test
    void permitsSameSimpleNameInDifferentNamespaces() throws IOException {
        write("org/sales/model.vernac", "namespace org.sales; value Title(String value);");
        write("org/support/model.vernac", "namespace org.support; value Title(String value);");
        var index = compiler.readProject(root).symbols();
        assertTrue(index.find("org.sales.Title").isPresent());
        assertTrue(index.find("org.support.Title").isPresent());
    }

    @Test
    void reportsDuplicateDeclarationsWithBothFileLocations() throws IOException {
        Path first = write("org/example/first.vernac", "namespace org.example;\nvalue Title(String value);");
        Path second = write("org/example/second.vernac", "namespace org.example;\nvalue Title(String value);");
        var error = assertThrows(SemanticValidationException.class, () -> compiler.readProject(root));
        assertEquals(2, error.diagnostics().size());
        assertTrue(error.getMessage().contains(first + ":2:1"));
        assertTrue(error.getMessage().contains(second + ":2:1"));
        assertTrue(error.getMessage().contains("Duplicate type declaration 'org.example.Title'"));
    }

    @Test
    void reportsDuplicateDeclarationsWithinOneFile() throws IOException {
        Path file = write("org/example/model.vernac", "namespace org.example;\nid TaskId;\nid TaskId;");
        var error = assertThrows(SemanticValidationException.class, () -> compiler.readProject(root));
        assertTrue(error.getMessage().contains(file + ":2:1"));
        assertTrue(error.getMessage().contains(file + ":3:1"));
    }

    @Test
    void reservesBuiltinTypeNamesAtTheirDeclaration() throws IOException {
        Path file = write("org/example/model.vernac", "namespace org.example;\nvalue Currency(String value);");
        var error = assertThrows(SemanticValidationException.class, () -> compiler.readProject(root));
        assertTrue(error.getMessage().contains("Type name 'Currency' is reserved"));
        assertEquals(file.toString(), error.diagnostics().getFirst().location().sourceName());
        assertEquals(2, error.diagnostics().getFirst().location().line());
    }

    @Test
    void includesEnumsAndCollectionsInTheSameCollisionDomain() throws IOException {
        write("org/example/values.vernac", "namespace org.example; value Tag(String value) collection Tags; value State = NEW | DONE;");
        var index = compiler.readProject(root).symbols();
        assertEquals(TypeSymbol.Kind.COLLECTION, index.find("org.example.Tags").orElseThrow().kind());
        assertEquals(TypeSymbol.Kind.ENUM, index.find("org.example.State").orElseThrow().kind());
        write("org/example/collision.vernac", "namespace org.example; value Tags(String value);");
        var error = assertThrows(SemanticValidationException.class, () -> compiler.readProject(root));
        assertTrue(error.getMessage().contains("Duplicate type declaration 'org.example.Tags'"));
    }

    @Test
    void requiresDirectoryToMatchTheNamespaceExactly() throws IOException {
        Path file = write("org/example/wrong/model.vernac", "namespace org.example.tasks; id TaskId;");
        var error = assertThrows(SemanticValidationException.class, () -> compiler.readProject(root));
        assertTrue(error.getMessage().contains("Move the file or correct its namespace"));
        assertTrue(error.getMessage().contains(Path.of("org/example/tasks").toString()));
        assertEquals(file.toString(), error.diagnostics().getFirst().location().sourceName());
    }

    @Test
    void rejectsSourceRootFilesInsteadOfInventingADefaultNamespace() throws IOException {
        write("model.vernac", "namespace org.example; id TaskId;");
        var error = assertThrows(SemanticValidationException.class, () -> compiler.readProject(root));
        assertTrue(error.getMessage().contains("requires directory"));
    }

    @Test
    void acceptsVernacKeywordsAndJavaContextualWordsInNamespaceSegments() throws IOException {
        write("org/custom/value/record/model.vernac", "namespace org.custom.value.record; id TaskId;");
        assertTrue(compiler.readProject(root).symbols().find("org.custom.value.record.TaskId").isPresent());
    }

    @Test
    void rejectsJavaKeywordsInNamespaceWithAVernacDiagnostic() throws IOException {
        write("org/class/model.vernac", "namespace org.class; id TaskId;");
        var error = assertThrows(SemanticValidationException.class, () -> compiler.readProject(root));
        assertTrue(error.getMessage().contains("Invalid namespace 'org.class'"));
        assertTrue(error.getMessage().contains("Java-compatible"));
    }

    @Test
    void scansOnlyVernacFilesAndAcceptsAnEmptyTree() throws IOException {
        write("README.md", "not a Vernac source");
        write("org/example/model.vernac.bak", "invalid source");
        assertTrue(compiler.readProject(root).sources().isEmpty());
    }

    @Test
    void rejectsAMissingSourceRoot() {
        assertThrows(IOException.class, () -> compiler.readProject(root.resolve("missing")));
    }

    @Test
    void collectsSyntaxErrorsFromDifferentFiles() throws IOException {
        Path first = write("org/example/first.vernac", "value MissingNamespace(String value);");
        Path second = write("org/example/second.vernac", "namespace org.example; value Broken(;");
        var error = assertThrows(SemanticValidationException.class, () -> compiler.readProject(root));
        assertEquals(2, error.diagnostics().size());
        assertTrue(error.getMessage().contains(first.toString()));
        assertTrue(error.getMessage().contains(second.toString()));
    }

    @Test
    void preservesImportsWithoutFollowingThemDuringIndexing() throws IOException {
        write("org/first/model.vernac", "namespace org.first; import org.second.*; value First(Second value);");
        write("org/second/model.vernac", "namespace org.second; import org.first.*; value Second(First value);");
        var project = compiler.readProject(root);
        assertEquals(2, project.sources().size());
        assertEquals(List.of("org.second.*"), project.sources().getFirst().unit().imports().stream().map(org.vernac.compiler.ast.ImportNode::text).toList());
        assertTrue(project.symbols().find("org.first.First").isPresent());
        // This stage indexes declarations; it does not approve reference cycles or validate imports.
    }
}
