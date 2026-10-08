// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.junit.jupiter.api.Test;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.testutil.InMemoryJavaCompiler;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MemberNamingContractTest {
    private VernacCompilationResult compile(String body) {
        return new VernacCompiler().compileSource("namespace naming; " + body);
    }

    private String failure(String body) {
        return assertThrows(SemanticValidationException.class, () -> compile(body)).getMessage();
    }

    @Test void singleAndMultipleFieldsHaveTheSameNamesAndNoAliases() throws Exception {
        var output = InMemoryJavaCompiler.compile(compile("""
                id TaskId;
                value Single(String);
                value Multiple(String, BigDecimal);
                value Explicit(String value);
                value Reference(TaskId);
                value Amount(int);
                value Behavior(String) behavior {
                    public String join(String) { return self.string() + string; }
                }
                """));
        assertTrue(output.success(), output.diagnostics().toString());
        for (String type : List.of("Single", "Multiple", "Behavior")) {
            var clazz = output.loadClass("naming.domain." + type);
            assertNotNull(clazz.getMethod("string"));
            assertThrows(NoSuchMethodException.class, () -> clazz.getMethod("value"));
        }
        assertNotNull(output.loadClass("naming.domain.Multiple").getMethod("bigDecimal"));
        assertNotNull(output.loadClass("naming.domain.Explicit").getMethod("value"));
        assertNotNull(output.loadClass("naming.domain.Reference").getMethod("taskId"));
        assertNotNull(output.loadClass("naming.domain.Amount").getMethod("intValue"));
        assertNotNull(output.loadClass("naming.domain.TaskId").getMethod("value"));
        assertNotNull(output.loadClass("naming.domain.TaskId").getMethod("asString"));
    }

    @Test void acronymAndQualifiedNamesYieldCompilableGetters() throws Exception {
        var output = InMemoryJavaCompiler.compile(compile("""
                value URLValue(String value);
                value HTMLXMLMapper(String value);
                value HTML2XMLMapper(String value);
                value Größe(String value);
                value 𐐀name(String value);
                value Container(naming.URLValue, HTMLXMLMapper, HTML2XMLMapper, Größe, 𐐀name);
                """));
        assertTrue(output.success(), output.diagnostics().toString());
        var type = output.loadClass("naming.domain.Container");
        for (String getter : List.of("urlValue", "htmlxmlMapper", "html2XMLMapper", "größe", "𐐨name"))
            assertNotNull(type.getMethod(getter));
    }

    @Test void duplicateNamesExplainBothOriginsWithoutInventingNames() {
        String error = failure("value IntValue(int value); value Pair(int, IntValue);");
        assertTrue(error.contains("Duplicate field name 'intValue'"), error);
        assertTrue(error.contains("derived from type 'int'"), error);
        assertTrue(error.contains("derived from type 'IntValue'"), error);
        assertTrue(error.contains(" at "), error);
        assertDoesNotThrow(() -> compile("value IntValue(int value); value Pair(int count, IntValue limit);"));
        assertTrue(failure("value Pair(String, String);").contains("Duplicate field name 'string'"));
        assertTrue(failure("value Pair(String string, String);").contains("Duplicate field name 'string'"));
    }

    @Test void generatedAndInheritedGettersFailEvenWhenTextIsAlreadyUsed() {
        for (String name : List.of("toString", "hashCode", "getClass", "wait", "notify", "notifyAll", "finalize")) {
            String error = failure("value Example(String text, String " + name + ");");
            assertTrue(error.contains("conflicts with"), error);
            assertFalse(error.contains("for example 'text'"), error);
            assertTrue(error.contains(name + "()"), error);
        }
        assertTrue(failure("value Example(int clone);").contains("incompatible return type"));
        String derived = failure("value ToString(String value); value Holder(ToString);");
        assertTrue(derived.contains("derived from type 'ToString'"), derived);
    }

    @Test void checksResolvedCustomSignaturesAndGeneratedFactories() {
        String error = failure("""
                value Title(String value);
                value Example(String text) behavior {
                    public String accept(Title first) { return text; }
                    public int accept(naming.Title second) { return 0; }
                }
                """);
        assertTrue(error.contains("accept(naming.domain.Title)"), error);
        for (String declaration : List.of(
                "value Example(String text) behavior { public String text() { return text; } }",
                "value Example(String value) behavior { public String value() { return value; } }",
                "value Example(String text) behavior { public int toString() { return 0; } }",
                "value Example(String text) behavior { public String of(String text) { return text; } }",
                "value Example(String text, Integer? other) behavior { public String of(String text) { return text; } }",
                "value Example(String text) validates { require(!text.isEmpty(), \"required\"); } behavior { public void validate() {} }",
                "value Example(String text) behavior { public void wait(long duration, int nanos) {} }",
                "value Example(String text) behavior { private String clone() { return text; } }",
                "value Example(String text) behavior { private void finalize() {} }")) {
            assertTrue(failure(declaration).contains("conflicts with"), declaration);
        }
    }

    @Test void permitsLegalOverloadsAndPrivateBehaviorHelpers() throws Exception {
        var output = InMemoryJavaCompiler.compile(compile("""
                value Example(String equals) behavior {
                    public boolean equals(String other) { return self.equals().equals(other); }
                    public String toString(int ignored) { return self.equals(); }
                    public String clone() { return self.equals(); }
                    private String label(Example receiver) { return receiver.equals(); }
                }
                value CloneField(String clone);
                """));
        assertTrue(output.success(), output.diagnostics().toString());
        var type = output.loadClass("naming.domain.Example");
        Object instance = type.getMethod("of", String.class).invoke(null, "yes");
        assertEquals("yes", type.getMethod("equals").invoke(instance));
        assertEquals(true, type.getMethod("equals", String.class).invoke(instance, "yes"));
        assertThrows(NoSuchMethodException.class, () -> type.getDeclaredMethod("label"));
    }

    @Test void parameterNamesAndNullabilityDoNotDistinguishSignatures() {
        assertTrue(failure("""
                value Example(String value) behavior {
                    public String accept(String first) { return first; }
                    public String accept(String? second) { return value; }
                }
                """).contains("accept(java.lang.String)"));
        assertTrue(failure("""
                value Example(String value) behavior {
                    public String accept(String, String) { return value; }
                }
                """).contains("Duplicate parameter name 'string'"));
    }

    @Test void importedAndQualifiedReferencesHaveTheSameSignature(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        java.nio.file.Files.createDirectories(root.resolve("model"));
        java.nio.file.Files.createDirectories(root.resolve("consumer"));
        java.nio.file.Files.writeString(root.resolve("model/title.vernac"),
                "namespace model; value Title(String value);");
        java.nio.file.Files.writeString(root.resolve("consumer/example.vernac"), """
                namespace consumer;
                import model.Title;
                value Example(Title) behavior {
                    public String useTitle(Title first) { return first.value(); }
                    public String useTitle(model.Title second) { return second.value(); }
                }
                """);
        String error = assertThrows(SemanticValidationException.class,
                () -> new VernacCompiler().compileProject(root)).getMessage();
        assertTrue(error.contains("useTitle(model.domain.Title)"), error);
    }

    @Test void idSyntaxRejectsCustomization() {
        assertDoesNotThrow(() -> compile("id TaskId;"));
        for (String declaration : List.of("id TaskId(UUID other);", "id TaskId(String value);",
                "id TaskId { public String label() { return \"x\"; } }",
                "id TaskId validates { require(true, \"x\"); }"))
            assertThrows(SemanticValidationException.class, () -> compile(declaration));
    }
}
