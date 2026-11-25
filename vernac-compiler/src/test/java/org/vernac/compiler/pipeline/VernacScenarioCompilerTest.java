// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.format.VernacFormatter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class VernacScenarioCompilerTest {
    @TempDir Path project;
    private final VernacScenarioCompiler compiler = new VernacScenarioCompiler();
    private static final String SCENARIO = """
            namespace example.tests;
            import example.domain.*;
            suite Tasks {
                scenario completes {
                    given task = Task(Title("Try Vernac"), Status.OPEN);
                    given originalId = task.id;
                    when task.complete();
                    expect task.id equals originalId;
                    expect task.status equals Status.DONE;
                    expect task.title.string equals "Try Vernac";
                }
            }
            """;

    @Test
    void generatesNamedTestsAgainstMainDeclarationsAndRetiresDeletedCases() throws IOException {
        var main = mainModel();
        var tests = Files.createDirectories(project.resolve("src/test/vernac/example/tests"));
        var source = Files.writeString(tests.resolve("tasks.vernac"), SCENARIO);
        var root = project.resolve("src/test/vernac");
        var output = project.resolve("target/generated-test-sources/vernac");
        var bridge = output.resolve("org/vernac/generated/VernacScenariosTest.java");

        assertEquals(1, compiler.generate(root, compiler.discover(root), main, output));
        var java = Files.readString(bridge);
        assertTrue(java.contains("example.tests.Tasks.completes"), java);
        assertTrue(java.contains("fixture$task.complete()"), java);
        assertTrue(java.contains("src/test/vernac/example/tests/tasks.vernac:9:"), java);

        Files.delete(source);
        assertEquals(0, compiler.generate(root, compiler.discover(root), main, output));
        assertFalse(Files.readString(bridge).contains("completes"));
    }

    @Test
    void diagnosesWrongTypesUnknownBehaviorsAndDuplicateCasesAtTheirSource() throws IOException {
        var main = mainModel();
        var root = Files.createDirectories(project.resolve("src/test/vernac"));
        var source = root.resolve("tasks.vernac");
        var output = project.resolve("target/generated-test-sources/vernac");

        Files.writeString(source, SCENARIO.replace("equals Status.DONE", "equals \"DONE\""));
        assertDiagnostic(root, output, main, "tasks.vernac:9:", "Expected example.domain.Status, got java.lang.String");
        Files.writeString(source, SCENARIO.replace("task.complete()", "task.finish()"));
        assertDiagnostic(root, output, main, "tasks.vernac:7:", "Unknown public behavior 'finish'");
        Files.writeString(source, SCENARIO.replace("scenario completes {",
                "scenario completes { expect true equals true; }\n    scenario completes {"));
        assertDiagnostic(root, output, main, "tasks.vernac:5:", "Duplicate scenario");
        assertFalse(Files.exists(output));
    }

    @Test
    void bindsBehaviorResultsAndChecksDomainValidation() throws IOException {
        var main = mainModel();
        var root = Files.createDirectories(project.resolve("src/test/vernac"));
        var source = root.resolve("tasks.vernac");
        var output = project.resolve("target/generated-test-sources/vernac");
        Files.writeString(source, SCENARIO.replace("when task.complete();", """
                when task.complete();
                when current = task.currentStatus();
                expect current equals Status.DONE;
                expect Title(" ") is invalid;
                """));
        assertEquals(1, compiler.generate(root, compiler.discover(root), main, output));
        var bridge = Files.readString(output.resolve("org/vernac/generated/VernacScenariosTest.java"));
        assertTrue(bridge.contains("var fixture$current = fixture$task.currentStatus()"), bridge);
        assertTrue(bridge.contains("assertThrows(DomainValidationException.class"), bridge);

        Files.writeString(source, SCENARIO.replace("when task.complete();", "when result = task.complete();"));
        assertDiagnostic(root, output, main, "tasks.vernac:7:", "does not return a value");
    }

    @Test
    void completesFieldsAndUsecasesBeforeTheStatementIsFinished() throws IOException {
        var main = mainModel();
        String prefix = """
                namespace example.tests;
                import example.domain.*;
                suite Tasks using example.tests.Configuration {
                    scenario opens {
                        given task = Task(Title("Try Vernac"), Status.OPEN);
                """;
        String fields = prefix + "expect task.st";
        var field = compiler.complete("draft.vernac", fields, fields.length(), main);
        assertEquals(java.util.List.of("task.status"), field.candidates());
        assertEquals("task.st", fields.substring(field.start()));
        String usecases = prefix + "when result = invoke Cur";
        assertEquals(java.util.List.of("CurrentStatus"),
                compiler.complete("draft.vernac", usecases, usecases.length(), main).candidates());
    }

    @Test
    void profileSelectionRejectsAnEmptyRunAndRetiresPreviouslySelectedMethods() throws IOException {
        var main = mainModel();
        var root = Files.createDirectories(project.resolve("src/test/vernac"));
        var source = root.resolve("tasks.vernac");
        var output = project.resolve("target/generated-test-sources/vernac");
        Files.writeString(source, SCENARIO + """
                suite Persistence profile postgres using example.tests.Configuration {
                    scenario committed { expect true equals true; }
                }
                """);
        assertEquals(1, compiler.generate(root, compiler.discover(root), main, output));
        var bridge = output.resolve("org/vernac/generated/VernacScenariosTest.java");
        assertFalse(Files.readString(bridge).contains("committed"));
        assertEquals(1, compiler.generate(root, compiler.discover(root), main, output, "postgres"));
        assertTrue(Files.readString(bridge).contains("committed"));
        assertFalse(Files.readString(bridge).contains("completes"));
        var failure = assertThrows(SemanticValidationException.class,
                () -> compiler.generate(root, compiler.discover(root), main, output, "mvc"));
        assertTrue(failure.getMessage().contains("No Vernac scenarios match profile 'mvc'"));
    }

    @Test
    void formatsScenariosIdempotently() {
        String compact = "namespace example.tests; suite Tasks{scenario waits{expect true equals true;}}";
        String formatted = VernacFormatter.format(compact, VernacFormatter.Kind.TEST);
        assertNotEquals(compact, formatted);
        assertTrue(formatted.contains("        expect true equals true;"), formatted);
        assertEquals(formatted, VernacFormatter.format(formatted, VernacFormatter.Kind.TEST));
    }

    @Test
    void productionParserRejectsTestDeclarations() {
        assertThrows(SemanticValidationException.class,
                () -> new VernacSourceParser().parse("tasks.vernac", SCENARIO));
    }

    private void assertDiagnostic(Path root, Path output, ResolvedProject main, String location, String message)
            throws IOException {
        var sources = compiler.discover(root);
        var failure = assertThrows(SemanticValidationException.class,
                () -> compiler.generate(root, sources, main, output));
        assertTrue(failure.getMessage().contains(location), failure.getMessage());
        assertTrue(failure.getMessage().contains(message), failure.getMessage());
    }

    private ResolvedProject mainModel() throws IOException {
        var root = project.resolve("src/main/vernac");
        var directory = Files.createDirectories(root.resolve("example/domain"));
        Files.writeString(directory.resolve("tasks.vernac"), """
                namespace example.domain;
                id TaskId;
                value Title(String) validates { require(!self.string().isBlank(), "Blank title"); };
                value Status = OPEN | DONE;
                usecase CurrentStatus() returns Status behavior { execute { return Status.OPEN; } }
                aggregate Task[TaskId](Title title, mut Status) behavior {
                    modify void complete() { self.status(Status.DONE); }
                    read Status currentStatus() { return self.status(); }
                };
                """);
        return new VernacCompiler().analyzeProject(root);
    }
}
