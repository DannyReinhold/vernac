// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.debug;

import static org.junit.jupiter.api.Assertions.*;

import com.palantir.javapoet.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.persistence.SchemaModel;
import org.vernac.compiler.pipeline.*;

import java.nio.file.*;
import java.util.*;

class AdapterSourceMapTest {
    @TempDir Path root;
    private static final String MODEL =
            """
                namespace example.adapters;
                // 🧭 before source locations
                value Label(String);
                event Checked(Label) validates {
                    require(!self.label().string().isBlank(), "Label required");
                };
                port Archive { Label load(Label label); }
                adapter Local implements Archive {
                    behavior {
                        public Label load(Label label) {
                            String text = helper(label.string());
                            return Label.of(text);
                        }
                        private String helper(String text) {
                            return text.strip();
                        }
                    }
                    schema Payload(String text, String? note);
                    mapping output: Payload <- Label {
                        text <- source.string;
                    }
                    mapping manual: Label <- Payload {
                        java {
                            String text = source.text();
                            return Label.of(text);
                        }
                    }
                    mapping external: Label <- Payload implemented by example.External;
                }
                    adapter Handwritten implements Archive {
                    behavior { public Label load(Label label) implemented by example.External; }
                }
            usecase Echo(Label) returns Label behavior { execute { return label; } }
                adapter Api inbound http uses Echo echo {
                    base "/labels";
                    get { POST "/"; body Input; execute echo { label <- body.text; }
                        respond 200 Reply using output; }
                    schema Input(String text);
                    schema Reply(String text);
                    mapping output: Reply <- Label { text <- source.string; }
                    mapping manual: Label <- Input { java { return Label.of(source.text()); } }
                }
            """;

    private Path source(String text) throws Exception {
        Path source = root.resolve("example/adapters/model.vernac");
        Files.createDirectories(source.getParent());
        Files.writeString(source, text);
        return source;
    }

    @Test
    void mapsAdaptersJavaAndDeclarativeMappingsAndRemainingValidators() throws Exception {
        String text = MODEL.replace("\n", "\r\n");
        source(text);
        var result = new VernacCompiler().compileProject(root);
        var entries = result.debugMap().entries();
        for (String identity :
                List.of(
                        "example.adapters.adapter.outbound.__VernacBehavior_Local",
                        "example.adapters.adapter.inbound.http.Api",
                        "example.adapters.domain.__VernacValidation_Checked",
                        "example.adapters.adapter.outbound.mapping.__VernacMapping_Local_manual",
                        "example.adapters.adapter.outbound.mapping.LocalMappings",
                        "example.adapters.adapter.inbound.http.mapping.ApiMappings",
                        "example.adapters.adapter.inbound.http.mapping.__VernacMapping_Api_manual"))
            assertTrue(entries.stream().anyMatch(e -> e.javaClass().equals(identity)), identity);
        assertEquals(
                1,
                entries.stream()
                        .filter(e -> e.javaClass().contains(".__VernacValidation_"))
                        .count());
        assertFalse(
                entries.stream()
                        .anyMatch(
                                e ->
                                        e.javaClass().endsWith("_external")
                                                || e.javaClass().endsWith("_Handwritten")));
        var fields = entries.stream().filter(e -> e.javaClass().endsWith("Mappings")).toList();
        assertEquals(2, fields.size(), "Do not map the omitted optional note default");
        for (var entry : entries) {
            String java =
                    result.generatedFiles().stream()
                            .filter(
                                    f ->
                                            (f.packageName() + "." + f.typeSpec().name())
                                                    .equals(entry.javaClass()))
                            .findFirst()
                            .orElseThrow()
                            .toString();
            assertEquals(BehaviorSourceMap.hash(java), entry.javaHash());
            assertEquals(BehaviorSourceMap.hash(text), entry.sourceHash());
            assertTrue(java.contains('"' + entry.runtimeHash() + '"'));
            String original = text.lines().toList().get(entry.sourceLine() - 1);
            String actual = java.lines().toList().get(entry.javaLine() - 1);
            if (entry.javaClass().endsWith(".Api")) {
                assertTrue(original.contains("label <- body.text"));
                assertTrue(actual.contains("__arg0"));
            } else if (entry.javaClass().endsWith("Mappings")) {
                assertTrue(original.contains("text <- source.string"));
                assertTrue(actual.contains("__mapped0"));
            } else if (entry.javaClass().contains("Validation")) {
                assertTrue(original.contains("require("));
                assertFalse(actual.contains("throw new"));
            } else assertTrue(original.strip().contains(actual.strip()), actual);
        }
        Path output = root.resolve("generated");
        result.writeTo(output);
        assertEquals(result.debugMap(), BehaviorSourceMap.read(output));
        source("namespace example.adapters; value Label(String);");
        new VernacCompiler().compileProject(root).writeTo(output);
        assertTrue(BehaviorSourceMap.read(output).entries().isEmpty());
    }

    @Test
    void bindsAssignmentsByTargetRatherThanDeclarationOrder() throws Exception {
        source(
                MODEL.replace("String? note", "String note")
                        .replace(
                                "text <- source.string;\n",
                                "note <- source.string;\n        text <- source.string;\n"));
        var result = new VernacCompiler().compileProject(root);
        var entries =
                result.debugMap().entries().stream()
                        .filter(e -> e.javaClass().endsWith("LocalMappings"))
                        .toList();
        assertEquals(2, entries.size());
        var original = Files.readAllLines(root.resolve("example/adapters/model.vernac"));
        assertTrue(original.get(entries.getFirst().sourceLine() - 1).contains("text <-"));
        assertTrue(original.get(entries.getLast().sourceLine() - 1).contains("note <-"));
    }

    @Test
    void rejectsChangedGeneratedAssignmentShapeInsteadOfGuessing() throws Exception {
        source(MODEL);
        var compiler = new VernacCompiler();
        var resolved = compiler.analyzeProject(root);
        var result = compiler.generate(resolved, SchemaModel.empty(), Map.of());
        var altered =
                result.generatedFiles().stream()
                        .map(
                                file -> {
                                    if (!file.typeSpec().name().equals("LocalMappings"))
                                        return file;
                                    var type =
                                            TypeSpec.classBuilder(file.typeSpec().name())
                                                    .addFields(file.typeSpec().fieldSpecs());
                                    type.addMethod(
                                            MethodSpec.methodBuilder("output")
                                                    .addStatement("return null")
                                                    .build());
                                    return JavaFile.builder(file.packageName(), type.build())
                                            .build();
                                })
                        .toList();
        assertFalse(
                BehaviorSourceMap.build(resolved, altered).entries().stream()
                        .anyMatch(e -> e.javaClass().endsWith("LocalMappings")));
    }
}
