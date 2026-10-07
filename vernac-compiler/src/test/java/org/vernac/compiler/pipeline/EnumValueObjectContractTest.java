// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.testutil.InMemoryJavaCompiler;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EnumValueObjectContractTest {
    private VernacCompilationResult compile(String body) {
        return new VernacCompiler().compileSource("namespace enums; " + body);
    }

    @Test void unusualConstantsAreValuesNotTypesAndProduceCompilableJava() throws Exception {
        var result = compile("""
                value MeinTyp(int);
                id MeineId;
                value MeinEnum = MeinTyp | MeineId | String | name | values | toString | dbValue
                        | value | custom | Größe | 𐐀name {
                    public String label() { return name(); }
                    public MeinTyp echo(MeinTyp input) { return input; }
                    public String number(int input) { return java.lang.String.valueOf(input); }
                };
                value Other = String | name;
                value Holder(MeinEnum);
                """);
        var output = InMemoryJavaCompiler.compile(result);
        assertTrue(output.success(), output.diagnostics().toString());
        var enumType = output.loadClass("enums.domain.MeinEnum");
        assertTrue(enumType.isEnum());
        Object[] constants = enumType.getEnumConstants();
        assertEquals(List.of("MeinTyp", "MeineId", "String", "name", "values", "toString", "dbValue",
                        "value", "custom", "Größe", "𐐀name"),
                Arrays.stream(constants).map(c -> ((Enum<?>) c).name()).toList());
        assertSame(constants[2], enumType.getMethod("valueOf", String.class).invoke(null, "String"));
        assertEquals("String", enumType.getMethod("label").invoke(constants[2]));
        assertEquals("42", enumType.getMethod("number", int.class).invoke(constants[2], 42));
        var domainType = output.loadClass("enums.domain.MeinTyp");
        Object wrapped = domainType.getMethod("of", int.class).invoke(null, 7);
        assertSame(wrapped, enumType.getMethod("echo", domainType).invoke(constants[0], wrapped));
        assertNotEquals(constants[2], output.loadClass("enums.domain.Other").getEnumConstants()[0]);
        Object[] values = (Object[]) enumType.getMethod("values").invoke(null);
        values[0] = null;
        assertNotNull(((Object[]) enumType.getMethod("values").invoke(null))[0]);
        for (String helper : List.of("dbValue", "of", "create", "asString", "value"))
            assertTrue(Arrays.stream(enumType.getDeclaredMethods()).noneMatch(m -> m.getName().equals(helper)), helper);
        assertTrue(Arrays.stream(enumType.getConstructors()).findAny().isEmpty());
        var error = assertThrows(InvocationTargetException.class,
                () -> enumType.getMethod("valueOf", String.class).invoke(null, "string"));
        assertInstanceOf(IllegalArgumentException.class, error.getCause());
        assertTrue(result.generatedFiles().stream().allMatch(f -> f.packageName().equals("enums.domain")));
        assertTrue(result.generatedFiles().stream().allMatch(f -> f.toString().contains("@NullMarked")));
    }

    @Test void allowsCustomToStringOverloadsAndPreviouslyGeneratedHelperNames() throws Exception {
        var output = InMemoryJavaCompiler.compile(compile("""
                value Status = PENDING | COMPLETED {
                    public String toString() { return "status:" + name(); }
                    public String name(int ignored) { return name(); }
                    public String values(int ignored) { return name(); }
                    public String of() { return name(); }
                    public String dbValue() { return "author-defined"; }
                    public boolean equals(String text) { return name().equals(text); }
                    public int compareTo(int ignored) { return 0; }
                };
                """));
        assertTrue(output.success(), output.diagnostics().toString());
        var type = output.loadClass("enums.domain.Status");
        var pending = type.getEnumConstants()[0];
        assertEquals("status:PENDING", pending.toString());
        assertEquals("PENDING", ((Enum<?>) pending).name());
        assertEquals("author-defined", type.getMethod("dbValue").invoke(pending));
    }

    @Test void rejectsActualMethodConflictsAndIncompatibleOverrides() {
        for (String method : List.of(
                "public String name() { return \"x\"; }",
                "public int ordinal() { return 0; }",
                "public int hashCode() { return 0; }",
                "public String values() { return \"x\"; }",
                "public Status valueOf(String input) { return A; }",
                "public String getDeclaringClass() { return \"x\"; }",
                "public String describeConstable() { return \"x\"; }",
                "public Status clone() { return this; }",
                "public void finalize() {}",
                "public void notify() {}",
                "public int compareTo(Status other) { return 0; }",
                "public int compareTo(enums.Status other) { return 0; }",
                "public int toString() { return 0; }",
                "private String toString() { return \"x\"; }",
                "public String? toString() { return Optional.empty(); }")) {
            var error = assertThrows(SemanticValidationException.class,
                    () -> compile("value Status = A { " + method + " };"), method);
            assertTrue(error.getMessage().contains("conflicts with"), error.getMessage());
        }
        assertThrows(SemanticValidationException.class, () -> compile("""
                value Status = A {
                    public int compare(Status first) { return 0; }
                    public String compare(enums.Status second) { return "x"; }
                };
                """));
    }

    @Test void rejectsEmptyDuplicateInvalidAndLegacyDefinitions() {
        for (String body : List.of("value Status = ;", "value Status = A | A;",
                "value Status = A | class;", "value String = A;",
                "value Status = A(\"external-code\");",
                "value Status = A { package other; };",
                "value Status = A validates { require(true, \"x\"); };",
                "value Status = A { String data; };"))
            assertThrows(SemanticValidationException.class, () -> compile(body), body);
    }

    @Test void enumsWorkAsRequiredAndOptionalVoFieldsAcrossNamespaces(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("states"));
        Files.createDirectories(root.resolve("views"));
        Files.writeString(root.resolve("states/status.vernac"), "namespace states; value Status = PENDING | COMPLETED;");
        Files.writeString(root.resolve("states/local.vernac"), "namespace states; value Local(Status);");
        Files.writeString(root.resolve("views/view.vernac"), """
                namespace views;
                import states.Status;
                value View(Status, states.Status? previousStatus);
                """);
        var project = new VernacCompiler().compileProject(root);
        var output = InMemoryJavaCompiler.compile(project.generatedFiles());
        assertTrue(output.success(), output.diagnostics().toString());
        var status = output.loadClass("states.domain.Status");
        var view = output.loadClass("views.domain.View");
        Object pending = status.getEnumConstants()[0];
        Object first = view.getMethod("of", status).invoke(null, pending);
        Object second = view.getMethod("of", status, status).invoke(null, pending, null);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertSame(pending, view.getMethod("status").invoke(first));
        assertEquals(Optional.empty(), view.getMethod("previousStatus").invoke(first));
        Object withPrevious = view.getMethod("of", status, status).invoke(null, pending, pending);
        assertEquals(Optional.of(pending), view.getMethod("previousStatus").invoke(withPrevious));
        assertNotEquals(first, withPrevious);
        var error = assertThrows(InvocationTargetException.class,
                () -> view.getMethod("of", status).invoke(null, new Object[]{null}));
        assertInstanceOf(org.vernac.runtime.DomainValidationException.class, error.getCause());
        assertNotNull(output.loadClass("states.domain.Local").getMethod("status"));
    }

    @Test void persistenceDoesNotSilentlyReplaceDbValueWithNames() {
        var result = compile("value Status = A;");
        // Exercise the shared flattening boundary directly; persistence is not yet redesigned.
        var unit = new VernacSourceParser().parse("mapping.vernac", "namespace enums; value Status = A;");
        var location = new org.vernac.compiler.ast.SourceLocation("mapping.vernac", 1, 1);
        var field = new org.vernac.compiler.ast.FieldNode(location,
                new org.vernac.compiler.ast.TypeNode(location, "Status"), "status");
        var error = assertThrows(SemanticValidationException.class, () ->
                org.vernac.compiler.generator.PostgresSchemaUtils.flattenField(field, "aggregate",
                        Map.of("Status", unit.valueObjects().getFirst()), "enums.domain"));
        assertTrue(error.getMessage().contains("explicit external-code mapping"));
        assertFalse(result.generatedFiles().isEmpty());
    }
}
