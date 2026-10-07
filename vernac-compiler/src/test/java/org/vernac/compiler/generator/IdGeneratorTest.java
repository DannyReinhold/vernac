// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.generator;

import com.palantir.javapoet.JavaFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vernac.compiler.ast.IdDeclarationNode;
import org.vernac.compiler.ast.SourceLocation;

import java.util.List;
import java.util.UUID;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.vernac.compiler.testutil.InMemoryJavaCompiler;
import org.vernac.compiler.pipeline.VernacCompiler;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.runtime.DomainValidationException;
import static org.junit.jupiter.api.Assertions.*;

import static org.assertj.core.api.Assertions.assertThat;

class IdGeneratorTest {

    private final IdGenerator generator = new IdGenerator();

    @Test
    @DisplayName("Generiert typsicheres ID-Value-Object mit UUID-Kapselung, Factory-Methoden und Standardverträgen")
    void shouldGenerateCompleteIdClass() {
        IdDeclarationNode node = new IdDeclarationNode(new SourceLocation(1, 1), "ProjectId");
        JavaFile file = generator.generate(node, "com.example");
        String code = file.toString().replaceAll("\\s+", " ");

        assertThat(code)
                .contains("package com.example.domain;")
                .contains("public final class ProjectId")
                .contains("private final UUID value;")
                .contains("private ProjectId(UUID value)")
                .contains("public static ProjectId create() { return new ProjectId(UUID.randomUUID()); }")
                .contains("public static ProjectId of(UUID value) { return new ProjectId(value); }")
                .contains("public static ProjectId of(String value)")
                .contains("UUID.fromString(value)")
                .contains("public UUID value() { return this.value; }")
                .contains("public boolean equals(@Nullable Object o)")
                .contains("public int hashCode()")
                .contains("public String toString() { return this.value.toString(); }");
    }

    @Test
    @DisplayName("IDs always append the domain role package, even for namespaces ending in domain")
    void shouldAlwaysAppendDomainPackage() {
        var node = new IdDeclarationNode(new SourceLocation(1, 1), "SharedId");
        var file = generator.generate(node, "com.example.domain");
        assertThat(file.packageName()).isEqualTo("com.example.domain.domain");
        assertThat(file.typeSpec().name()).isEqualTo("SharedId");
    }

    @Test
    void generatedIdsEnforceTheirRuntimeContract() throws Exception {
        var result = new VernacCompiler().compileSource("namespace contract.ids; id TaskId; id OwnerId;");
        var compiled = InMemoryJavaCompiler.compile(result);
        assertTrue(compiled.success(), compiled.diagnostics().toString());
        var taskId = compiled.loadClass("contract.ids.domain.TaskId");
        var ownerId = compiled.loadClass("contract.ids.domain.OwnerId");
        assertTrue(taskId.isAnnotationPresent(NullMarked.class));
        assertTrue(taskId.getMethod("equals", Object.class).getAnnotatedParameterTypes()[0].isAnnotationPresent(Nullable.class));
        assertTrue(Modifier.isFinal(taskId.getModifiers()));
        assertEquals(0, taskId.getConstructors().length);
        UUID uuid = UUID.randomUUID();
        var fromUuid = taskId.getMethod("of", UUID.class).invoke(null, uuid);
        var fromText = taskId.getMethod("of", String.class).invoke(null, uuid.toString());
        assertEquals(fromUuid, fromText);
        assertEquals(fromUuid.hashCode(), fromText.hashCode());
        assertEquals(uuid, taskId.getMethod("value").invoke(fromUuid));
        assertEquals(uuid.toString(), fromUuid.toString());
        assertNotEquals(fromUuid, ownerId.getMethod("of", UUID.class).invoke(null, uuid));
        assertFalse(fromUuid.equals(null));
        var created = taskId.getMethod("create").invoke(null);
        assertInstanceOf(UUID.class, taskId.getMethod("value").invoke(created));
        for (Class<?> input : List.of(UUID.class, String.class)) {
            var rejected = assertThrows(InvocationTargetException.class,
                    () -> taskId.getMethod("of", input).invoke(null, new Object[]{null}));
            assertInstanceOf(DomainValidationException.class, rejected.getCause());
        }
        var malformed = assertThrows(InvocationTargetException.class,
                () -> taskId.getMethod("of", String.class).invoke(null, "invalid"));
        assertInstanceOf(DomainValidationException.class, malformed.getCause());
        assertInstanceOf(IllegalArgumentException.class, malformed.getCause().getCause());
    }

    @Test
    void rejectsRemovedIdPackageOverrides() {
        assertThrows(SemanticValidationException.class, () -> new VernacCompiler().compileSource("""
                namespace contract.ids;
                id TaskId { package other.place; }
                """));
    }

}