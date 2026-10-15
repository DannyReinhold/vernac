// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.debug;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.pipeline.VernacCompiler;

import java.nio.file.*;
import java.util.*;

class ValidationSourceMapTest {
    @TempDir Path root;

    @Test
    void mapsRequireEntryPointsForDomainUsecaseAndOverloadedServiceValidators() throws Exception {
        Path source = root.resolve("example/checks/model.vernac");
        Files.createDirectories(source.getParent());
        String text =
                """
                namespace example.checks;
                // 🧭 code points before source positions
                value Name(String) validates {
                    require(
                        !self.string().isBlank(),
                        "A name is required"
                    );
                    require(self.string().length() < 40, "Too long");
                };
                id ParcelId;
                entity Parcel[ParcelId](mut Name) validates {
                    require(self.name().string().length() > 1, "Too short");
                };
                id ShipmentId;
                aggregate Shipment[ShipmentId](Name, Parcel) validates {
                    require(self.name().string().length() > 1, "Too short");
                };
                usecase Check(Name) returns Name validates {
                    require(self.name().string().length() > 1, "Too short");
                } behavior { execute { return name; } }
                service Policy behavior {
                    private Name helper(Name name) { return name; }
                    public Name check(Name name)
                    validates { require(self.name().string().length() > 1, "Too short"); } {
                        return helper(name);
                    }
                    public Name check(Name first, Name second)
                    validates { require(!self.first().equals(self.second()), "Different names required"); } {
                        return first;
                    }
                }
                """
                        .replace("\n", "\r\n");
        Files.writeString(source, text);
        var result = new VernacCompiler().compileProject(root);
        var entries =
                result.debugMap().entries().stream()
                        .filter(e -> e.javaClass().contains(".__VernacValidation_"))
                        .toList();
        assertEquals(7, entries.size(), "Exactly one breakpoint entry per require");
        assertEquals(
                Set.of("validate1", "validate2"),
                new HashSet<>(
                        entries.stream()
                                .filter(e -> e.javaClass().endsWith("_Policy"))
                                .map(BehaviorSourceMap.Entry::method)
                                .toList()));
        assertEquals(
                5, entries.stream().map(BehaviorSourceMap.Entry::javaClass).distinct().count());
        assertEquals(
                7,
                entries.stream().map(e -> e.javaClass() + ":" + e.javaLine()).distinct().count());
        for (var entry : entries) {
            assertTrue(text.lines().toList().get(entry.sourceLine() - 1).contains("require("));
            String java =
                    result.generatedFiles().stream()
                            .filter(
                                    f ->
                                            (f.packageName() + "." + f.typeSpec().name())
                                                    .equals(entry.javaClass()))
                            .findFirst()
                            .orElseThrow()
                            .toString();
            assertFalse(java.lines().toList().get(entry.javaLine() - 1).contains("throw new"));
            assertEquals(BehaviorSourceMap.hash(text), entry.sourceHash());
            assertEquals(BehaviorSourceMap.hash(java), entry.javaHash());
            assertEquals(64, entry.runtimeHash().length());
            assertEquals("example/checks/model.vernac", entry.source());
        }
        Path output = root.resolve("generated");
        result.writeTo(output);
        assertEquals(result.debugMap(), BehaviorSourceMap.read(output));
        Files.writeString(source, "namespace example.checks; value Name(String);");
        new VernacCompiler().compileProject(root).writeTo(output);
        assertTrue(BehaviorSourceMap.read(output).entries().isEmpty());
    }

    @Test
    void doesNotInventMappingsWhenTheGeneratedValidationConditionDiffers() throws Exception {
        Path source = root.resolve("example/checks/name.vernac");
        Files.createDirectories(source.getParent());
        Files.writeString(
                source,
                "namespace example.checks; value Name(String) validates {"
                    + " require(!self.string().isBlank(), \"Nonempty\"); };");
        var compiler = new VernacCompiler();
        var resolved = compiler.analyzeProject(root);
        var generated =
                compiler.generate(
                        resolved, org.vernac.compiler.persistence.SchemaModel.empty(), Map.of());
        var altered =
                generated.generatedFiles().stream()
                        .map(
                                file -> {
                                    if (!file.typeSpec().name().equals("__VernacValidation_Name"))
                                        return file;
                                    var type =
                                            com.palantir.javapoet.TypeSpec.classBuilder(
                                                            file.typeSpec().name())
                                                    .addFields(file.typeSpec().fieldSpecs());
                                    type.addMethod(
                                            com.palantir.javapoet.MethodSpec.methodBuilder(
                                                            "validate")
                                                    .addModifiers(
                                                            javax.lang.model.element.Modifier
                                                                    .STATIC)
                                                    .addCode(
                                                            "if (true) { throw new"
                                                                + " RuntimeException(); }\n")
                                                    .build());
                                    return com.palantir.javapoet.JavaFile.builder(
                                                    file.packageName(), type.build())
                                            .build();
                                })
                        .toList();
        assertTrue(BehaviorSourceMap.build(resolved, altered).entries().isEmpty());
    }
}
