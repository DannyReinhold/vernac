// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import com.palantir.javapoet.JavaFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.testutil.InMemoryJavaCompiler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class CompilerEntryPointTest {
    @TempDir Path root;

    private Path write(String source) throws Exception {
        Path file = root.resolve("example/domain/model.vernac");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return file;
    }

    private Map<String, String> sources(List<JavaFile> files) {
        Map<String, String> result = new TreeMap<>();
        for (var file : files) assertNull(result.put(file.packageName() + "." + file.typeSpec().name(), file.toString()));
        return result;
    }

    @Test void allEntryPointsUseTheSameResolvedGeneratorsAndStoragePlan() throws Exception {
        String source = """
                namespace example.domain;
                id RootId; id ChildId;
                value Label(String);
                entity Child[ChildId](Label) list Children;
                aggregate Root[RootId](Children, Label) list Roots;
                repository RootRepository for Root { find Roots all(); }
                recorded event Created(RootId);
                """;
        var compiler = new VernacCompiler();
        Path file = write(source);
        var project = compiler.compileProject(root);
        assertEquals(sources(project.generatedFiles()), sources(compiler.compile(file).generatedFiles()));
        assertEquals(sources(project.generatedFiles()), sources(compiler.compileSource(source).generatedFiles()));
        assertTrue(project.generatedFiles().stream().anyMatch(f -> f.packageName().equals("example.domain.domain")
                && f.typeSpec().name().equals("Root")));
        assertTrue(project.generatedFiles().stream().anyMatch(f -> f.typeSpec().name().equals("JdbcRootRepository")));
        // The JDBC bindings are compiled by Maven's integration tests with runtime-jdbc on the classpath.
        var domainFiles = project.generatedFiles().stream().filter(f -> !f.packageName().contains(".adapter.")).toList();
        var compiled = InMemoryJavaCompiler.compile(domainFiles);
        assertTrue(compiled.success(), compiled.diagnostics().toString());
    }

    @Test void singleFileAndProjectCompilationReportTheSameSemanticProblems() throws Exception {
        String source = "namespace example.domain; value IntValue(int); value Conflict(int, IntValue);";
        Path file = write(source);
        var compiler = new VernacCompiler();
        var projectError = assertThrows(SemanticValidationException.class, () -> compiler.compileProject(root));
        var fileError = assertThrows(SemanticValidationException.class, () -> compiler.compile(file));
        assertEquals(projectError.getMessage(), fileError.getMessage());
        var memoryError = assertThrows(SemanticValidationException.class, () -> compiler.compileSource(source));
        assertTrue(memoryError.getMessage().contains("intValue"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "value Text(@Nullable String text);",
            "aggregate Root[RootId]() { public void change() {} };",
            "entity Child[ChildId]() { package example.other; };",
            "repository Roots for Root { package example.other; }",
            "repository Roots for Root { custom Root lookup(); }"
    })
    void obsoleteOrIgnoredSyntaxIsRejectedAtTheParser(String declaration) {
        var error = assertThrows(SemanticValidationException.class, () -> new VernacCompiler().compileSource(
                "namespace example; id RootId; id ChildId; " + declaration));
        assertTrue(error.getMessage().contains("Syntax error"), error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"RootId?", "RootId<String>"})
    void identitySyntaxPreservesOptionalityAndTypeArgumentsForValidation(String type) {
        var error = assertThrows(SemanticValidationException.class, () -> new VernacCompiler().compileSource(
                "namespace example; id RootId; aggregate Root[" + type + "]();"));
        assertTrue(error.getMessage().contains("identity requires a Vernac id type"), error.getMessage());
    }
}
