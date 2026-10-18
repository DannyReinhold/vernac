// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.debug;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.pipeline.VernacCompiler;

import java.nio.file.*;

class BehaviorSourceMapTest {
    @TempDir Path root;

    @Test
    void mapsFinalJavaLinesAndKeepsHashesPortableAcrossUnicodeCrLfAndOverloads() throws Exception {
        Path source = root.resolve("src/main/vernac/example/names/name.vernac");
        Files.createDirectories(source.getParent());
        String text =
                """
                namespace example.names;
                // supplementary Unicode before the body: 🧭
                value Name(String) behavior {
                    /* 🧭 */ public String display(String suffix) {
                        String upper = self.string().toUpperCase();
                        return upper + suffix;
                    }
                    public String display(int count) {
                        return self.string().repeat(count);
                    }
                    private String suffix() { return "!"; }
                    public String external() implemented by example.External;
                } list Names behavior {
                    public int total() { return self.size(); }
                };
                """
                        .replace("\n", "\r\n");
        assertEquals(
                BehaviorSourceMap.hash(text),
                BehaviorSourceMap.hash(text.replace("\r\n", "\n")),
                "Editor newline normalization must not make Windows sources stale");
        Files.writeString(source, text);
        var result = new VernacCompiler().compileProject(root.resolve("src/main/vernac"));
        var map = result.debugMap();
        assertFalse(map.entries().isEmpty());
        for (var entry : map.entries()) {
            var java =
                    result.generatedFiles().stream()
                            .filter(
                                    f ->
                                            (f.packageName() + "." + f.typeSpec().name())
                                                    .equals(entry.javaClass()))
                            .findFirst()
                            .orElseThrow()
                            .toString();
            assertTrue(
                    text.lines()
                            .toList()
                            .get(entry.sourceLine() - 1)
                            .contains(java.lines().toList().get(entry.javaLine() - 1).strip()));
            assertEquals(BehaviorSourceMap.hash(text), entry.sourceHash());
            assertEquals(BehaviorSourceMap.hash(java), entry.javaHash());
            assertEquals("example/names/name.vernac", entry.source());
        }
        assertTrue(map.entries().stream().anyMatch(e -> e.method().equals("suffix")));
        assertTrue(map.entries().stream().anyMatch(e -> e.javaClass().endsWith("_Names")));
        assertFalse(map.entries().stream().anyMatch(e -> e.method().equals("external")));
        Path output = root.resolve("target/generated-sources/vernac");
        result.writeTo(output);
        assertEquals(map, BehaviorSourceMap.read(output));
        String metadata = Files.readString(output.resolve(BehaviorSourceMap.FILE));
        assertFalse(metadata.contains(root.toString()));
        Files.writeString(source, "namespace example.names; id Ref;");
        new VernacCompiler().compileProject(root.resolve("src/main/vernac")).writeTo(output);
        assertTrue(
                BehaviorSourceMap.read(output).entries().isEmpty(),
                "Removed behaviors cannot retain stale mappings");
    }

    @Test
    void mapsUsecaseExecuteResultAndPrivateHelpersButNotExternalBodies() throws Exception {
        Path source = root.resolve("example/operations/format.vernac");
        Files.createDirectories(source.getParent());
        String text =
                """
                namespace example.operations;
                value Label(String);
                usecase Format(Label label, Label? alias) returns (Label label, Label? alias)
                validates { require(self.label().string().length() > 0, "Nonempty"); }
                behavior {
                    execute {
                        String normalized = label.string().strip();
                        return result(normalized, alias);
                    }
                    private Result result(String text, Label? alias) {
                        return Result.of(Label.of(text), alias.orElse(null));
                    }
                }
                usecase External(Label) returns Label behavior {
                    execute implemented by example.Handwritten;
                }
                """;
        Files.writeString(source, text);
        var result = new VernacCompiler().compileProject(root);
        var entries =
                result.debugMap().entries().stream()
                        .filter(e -> e.javaClass().contains(".__VernacBehavior_"))
                        .toList();
        assertTrue(
                result.debugMap().entries().stream()
                        .anyMatch(e -> e.javaClass().endsWith("__VernacValidation_Format")));
        assertFalse(entries.isEmpty());
        assertTrue(entries.stream().anyMatch(e -> e.method().equals("execute")));
        assertTrue(entries.stream().anyMatch(e -> e.method().equals("result")));
        for (var entry : entries) {
            assertEquals("example.operations.usecase.__VernacBehavior_Format", entry.javaClass());
            String java =
                    result.generatedFiles().stream()
                            .filter(
                                    f ->
                                            (f.packageName() + "." + f.typeSpec().name())
                                                    .equals(entry.javaClass()))
                            .findFirst()
                            .orElseThrow()
                            .toString();
            assertEquals(
                    text.lines().toList().get(entry.sourceLine() - 1).strip(),
                    java.lines().toList().get(entry.javaLine() - 1).strip());
            assertEquals(BehaviorSourceMap.hash(java), entry.javaHash());
            assertTrue(java.contains("import static example.operations.usecase.Format.Result;"));
            assertFalse(text.lines().toList().get(entry.sourceLine() - 1).contains("require("));
        }
        Files.writeString(
                source,
                "namespace example.operations; value Label(String); usecase Format(Label) returns"
                        + " Label behavior { execute implemented by example.Handwritten; }");
        assertTrue(new VernacCompiler().compileProject(root).debugMap().entries().isEmpty());
    }

    @Test
    void executeMappingDoesNotSelectAnEarlierHelperWithIdenticalCode() throws Exception {
        Path source = root.resolve("example/operations/repeated.vernac");
        Files.createDirectories(source.getParent());
        String text =
                """
                namespace example.operations;
                value Label(String);
                usecase Echo(Label label) returns Label behavior {
                    private Label helper(Label label) {
                        return label;
                    }
                    execute {
                        return label;
                    }
                }
                """;
        Files.writeString(source, text);
        var entries = new VernacCompiler().compileProject(root).debugMap().entries();
        var execute =
                entries.stream()
                        .filter(e -> e.method().equals("execute"))
                        .findFirst()
                        .orElseThrow();
        var helper =
                entries.stream().filter(e -> e.method().equals("helper")).findFirst().orElseThrow();
        assertTrue(execute.sourceLine() > helper.sourceLine());
        assertEquals("return label;", text.lines().toList().get(execute.sourceLine() - 1).strip());
    }

    @Test
    void mapsOverloadedServiceOperationsDependenciesAndHelpersWithoutValidationOrExternalBodies()
            throws Exception {
        Path source = root.resolve("example/operations/planning.vernac");
        Files.createDirectories(source.getParent());
        String text =
                """
                namespace example.operations;
                value Label(String);
                service Policy behavior {
                    public Label choose(Label label) {
                        return label;
                    }
                }
                service Planning uses Policy policy behavior {
                    public Label prepare(Label label)
                    validates { require(!self.label().string().isBlank(), "Nonempty"); } {
                        return self.policy().choose(label);
                    }
                    public Label prepare(Label label, Label suffix) {
                        return helper(label, suffix);
                    }
                    private Label helper(Label label, Label suffix) {
                        return Label.of(label.string() + suffix.string());
                    }
                    public Label external(Label label) implemented by example.External;
                }
                """;
        Files.writeString(source, text);
        var compilation = new VernacCompiler().compileProject(root);
        var entries =
                compilation.debugMap().entries().stream()
                        .filter(
                                e ->
                                        e.javaClass()
                                                .equals(
                                                        "example.operations.domain.__VernacBehavior_Planning"))
                        .toList();
        assertEquals(3, entries.size());
        assertEquals(2, entries.stream().filter(e -> e.method().equals("prepare")).count());
        assertTrue(entries.stream().anyMatch(e -> e.method().equals("helper")));
        String java =
                compilation.generatedFiles().stream()
                        .filter(f -> f.typeSpec().name().equals("__VernacBehavior_Planning"))
                        .findFirst()
                        .orElseThrow()
                        .toString();
        for (var entry : entries) {
            String line = text.lines().toList().get(entry.sourceLine() - 1).strip();
            assertEquals(line, java.lines().toList().get(entry.javaLine() - 1).strip());
            assertEquals(BehaviorSourceMap.hash(java), entry.javaHash());
            assertFalse(line.contains("require("));
            assertFalse(line.contains("implemented by"));
        }
        assertTrue(java.contains("PlanningAccess self"));
        var bean =
                compilation.generatedFiles().stream()
                        .filter(f -> f.typeSpec().name().equals("Planning"))
                        .findFirst()
                        .orElseThrow()
                        .toString();
        assertFalse(bean.contains("@Transactional"));
        Files.writeString(source, "namespace example.operations; value Label(String);");
        assertTrue(new VernacCompiler().compileProject(root).debugMap().entries().isEmpty());
    }
}
