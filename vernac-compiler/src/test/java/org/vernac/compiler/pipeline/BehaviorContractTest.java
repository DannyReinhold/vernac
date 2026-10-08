// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.junit.jupiter.api.Test;
import org.vernac.compiler.testutil.InMemoryJavaCompiler;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.runtime.BehaviorContractException;
import java.lang.reflect.InvocationTargetException;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

public class BehaviorContractTest {
    private VernacCompilationResult generate(String model) {
        return new VernacCompiler().compileSource("namespace behavior.tests; " + model);
    }
    private InMemoryJavaCompiler.CompilationOutput compile(String model) {
        var output = InMemoryJavaCompiler.compile(generate(model));
        assertTrue(output.success(), output.diagnostics().toString());
        return output;
    }

    @Test void inlineBehaviorHasExplicitReceiverPrivateHelpersAndScopedImports() throws Exception {
        var output = compile("""
                value PersonName(String) behavior {
                    java imports { java.util.Locale; }
                    public String upper() { return self.string().toUpperCase(Locale.ROOT) + suffix(2); }
                    private String suffix(int count) { return "!".repeat(count); }
                    public String? alternative() { return Optional.empty(); }
                } list Names behavior {
                    public int total() { return self.size(); }
                };
                """);
        var name = output.loadClass("behavior.tests.domain.PersonName");
        var instance = name.getMethod("of", String.class).invoke(null, "Hans");
        assertEquals("HANS!!", name.getMethod("upper").invoke(instance));
        assertEquals(Optional.empty(), name.getMethod("alternative").invoke(instance));
        assertThrows(NoSuchMethodException.class, () -> name.getDeclaredMethod("suffix", int.class));
        var helper = output.loadClass("behavior.tests.domain.__VernacBehavior_PersonName");
        assertNull(helper.getEnclosingClass());
        assertTrue(java.lang.reflect.Modifier.isPrivate(helper.getDeclaredMethod("suffix", int.class).getModifiers()));
        assertFalse(java.lang.reflect.Modifier.isPublic(helper.getModifiers()));
    }

    @Test void inlineCodeCannotReadPrivateStateOrBypassTheConstructor() {
        for (String expression : new String[]{"self.string", "new PersonName(\"Hans\").string()"}) {
            var output = InMemoryJavaCompiler.compile(generate(
                    "value PersonName(String) behavior { public String bad() { return " + expression + "; } };"));
            assertFalse(output.success());
            assertTrue(output.diagnostics().stream().anyMatch(d -> d.getMessage(java.util.Locale.ROOT).contains("private")));
        }
    }

    @Test void badNonNullAndOptionalResultsFailAtTheBehaviorBoundary() throws Exception {
        var output = compile("""
                value Name(String) behavior {
                    public String bad() { return null; }
                    public String? missing() { return null; }
                };
                """);
        var name = output.loadClass("behavior.tests.domain.Name");
        var instance = name.getMethod("of", String.class).invoke(null, "x");
        for (String method : new String[]{"bad", "missing"}) {
            var failure = assertThrows(InvocationTargetException.class, () -> name.getMethod(method).invoke(instance));
            assertInstanceOf(BehaviorContractException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("Name." + method));
        }
    }

    @Test void javaImportsCannotReplaceModelOrBuiltinTypes() {
        for (String model : new String[]{
                "value Name(String); value Other(String) behavior { java imports { foreign.Name; } };",
                "value Name(String) behavior { java imports { foreign.String; } };",
                "value Name(String) behavior { java imports { java.util.Date; java.sql.Date; } };"}) {
            var failure = assertThrows(SemanticValidationException.class, () -> generate(model));
            assertTrue(failure.getMessage().contains("conflicts with"));
        }
    }

    @Test void rejectsInvalidBehaviorDeclarationsBeforeJavac() {
        for (String model : new String[]{
                "value Name(String) behavior { protected String bad() { return self.string(); } };",
                "value Name(String) behavior { public String bad(String self) { return self; } };",
                "value Name(String) behavior { public @Nullable String bad() { return null; } };",
                "value Name(String) behavior { java imports { java.util.*; } };",
                "value Name(String) behavior { private String bad() implemented by x.Impl; };",
                "value Name(String) behavior { public String bad() implemented by Impl; };"})
            assertThrows(SemanticValidationException.class, () -> generate(model));
    }

    @Test void bodyOnlyModelReferencesAreImportedAcrossFiles(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        java.nio.file.Files.createDirectories(root.resolve("model"));
        java.nio.file.Files.createDirectories(root.resolve("view"));
        java.nio.file.Files.writeString(root.resolve("model/name.vernac"), "namespace model; value Name(String);");
        java.nio.file.Files.writeString(root.resolve("view/text.vernac"), """
                namespace view;
                import model.Name;
                value Text(String) behavior {
                    public String echo() { return Name.of(self.string()).string(); }
                    public String choose(String? alternative) {
                        return alternative == null ? self.string() : alternative;
                    }
                };
                """);
        var output = InMemoryJavaCompiler.compile(new VernacCompiler().compileProject(root).generatedFiles());
        assertTrue(output.success(), output.diagnostics().toString());
        var type = output.loadClass("view.domain.Text");
        var instance = type.getMethod("of", String.class).invoke(null, "hello");
        assertEquals("hello", type.getMethod("echo").invoke(instance));
        assertEquals("hello", type.getMethod("choose", String.class).invoke(instance, (Object) null));
    }

    @Test void missingExternalImplementationFailsJavaCompilation() {
        var output = InMemoryJavaCompiler.compile(generate(
                "value Name(String) behavior { public int length() implemented by missing.Implementation; };"));
        assertFalse(output.success());
    }

    @Test void rejectsLoweredHelperCollisionAndKnownRepositoryImport() {
        var lowered = assertThrows(SemanticValidationException.class, () -> generate("""
                value Name(String) behavior {
                    public String label(String suffix) { return suffix; }
                    private String label(Name receiver, String suffix) { return suffix; }
                };
                """));
        assertTrue(lowered.getMessage().contains("after adding the self receiver"));
        var dependency = assertThrows(SemanticValidationException.class, () -> generate("""
                id AccountId;
                aggregate Account[AccountId](int balance);
                repository Accounts for Account {}
                value Name(String) behavior {
                    java imports { behavior.tests.domain.Accounts; }
                };
                """));
        assertTrue(dependency.getMessage().contains("known repository or adapter"), dependency.getMessage());
    }

    @Test void externalStaticImplementationIsCalledWithSelf() throws Exception {
        var output = compile("""
                value Name(String) behavior {
                    public int length() implemented by org.vernac.compiler.pipeline.BehaviorContractTest.Implementation;
                };
                """);
        var name = output.loadClass("behavior.tests.domain.Name");
        var instance = name.getMethod("of", String.class).invoke(null, "Hans");
        assertEquals(4, name.getMethod("length").invoke(instance));
    }

    @Test void generatedSourceLocationsAreIndependentOfCheckoutDirectory(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var roots = java.util.List.of(directory.resolve("first/src/main/vernac"),
                directory.resolve("second/src/main/vernac"));
        var outputs = new java.util.ArrayList<String>();
        for (var root : roots) {
            var source = root.resolve("behavior/tests/näme.vernac");
            java.nio.file.Files.createDirectories(source.getParent());
            java.nio.file.Files.writeString(source, """
                    namespace behavior.tests;
                    value Name(String) behavior {
                        public String label() { return self.string(); }
                        private String suffix() { return "!"; }
                    };
                    """);
            var files = new VernacCompiler().compileProject(root).generatedFiles();
            String generated = files.stream().map(Object::toString)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertFalse(generated.contains(directory.toString()));
            assertTrue(generated.contains("Name.label (behavior/tests/näme.vernac:3:5)"), generated);
            assertTrue(generated.contains("Vernac source: behavior/tests/näme.vernac:3:5"), generated);
            assertTrue(generated.contains("Vernac source: behavior/tests/näme.vernac:4:5"), generated);
            outputs.add(generated);
        }
        assertEquals(outputs.get(0), outputs.get(1));
    }

    @Test void inMemoryBehaviorPreservesItsSyntheticSourceName() {
        String generated = generate("value Name(String) behavior { public String label() { return self.string(); } };")
                .generatedFiles().stream().map(Object::toString)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(generated.contains("<memory>:"));
    }

    public static final class Implementation {
        public static int length(Object self) {
            try { return ((String) self.getClass().getMethod("string").invoke(self)).length(); }
            catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
        }
    }
}
