// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.testutil.InMemoryJavaCompiler;
import org.vernac.compiler.tooling.EditorValueObjects;
import org.vernac.runtime.BehaviorContractException;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.*;
import java.util.*;

class ValueStringRepresentationTest {
    @TempDir Path root;
    private static final String MODEL =
            """
            namespace representation;
            value Secret(String encoded) validates {
                require(!self.encoded().isBlank(), "Secret encoding required");
            } behavior {
                public String toString() { return "Secret[REDACTED]"; }
                public String toString(int ignored) { return "overload"; }
            } list Secrets;
            value Holder(Secret secret, Secret? extra);
            id ContainerId;
            aggregate Container[ContainerId](Holder holder);
            value Ordinary(String text) behavior {
                public String toString(int ignored) { return "overload"; }
            };
            """;

    @Test
    void customRepresentationRedactsNestedValuesWithoutChangingStateOrEquality() throws Exception {
        var result = new VernacCompiler().compileSource(MODEL);
        var java = InMemoryJavaCompiler.compile(result);
        assertTrue(java.success(), java.diagnostics().toString());
        var secretType = java.loadClass("representation.domain.Secret");
        String seed = "seeded-secret-encoding-not-for-logs";
        var secret = secretType.getMethod("of", String.class).invoke(null, seed);
        var same = secretType.getMethod("of", String.class).invoke(null, seed);
        var other = secretType.getMethod("of", String.class).invoke(null, "another-secret");
        assertEquals(seed, secretType.getMethod("encoded").invoke(secret));
        assertEquals(secret, same);
        assertEquals(secret.hashCode(), same.hashCode());
        assertNotEquals(secret, other);
        assertEquals("Secret[REDACTED]", secret.toString());
        assertEquals("overload", secretType.getMethod("toString", int.class).invoke(secret, 1));
        var holderType = java.loadClass("representation.domain.Holder");
        var holder = holderType.getMethod("of", secretType, secretType).invoke(null, secret, other);
        var containerType = java.loadClass("representation.domain.Container");
        var container = containerType.getMethod("create", holderType).invoke(null, holder);
        var secretsType = java.loadClass("representation.domain.Secrets");
        var secrets =
                secretsType.getMethod("of", Iterable.class).invoke(null, List.of(secret, other));
        for (var value : List.of(holder, container, secrets, Map.of("value", secret))) {
            assertFalse(value.toString().contains(seed));
            assertFalse(value.toString().contains("another-secret"));
            assertTrue(value.toString().contains("REDACTED"));
        }
        var ordinaryType = java.loadClass("representation.domain.Ordinary");
        assertEquals(
                "Ordinary[text='visible']",
                ordinaryType.getMethod("of", String.class).invoke(null, "visible").toString());
    }

    @Test
    void invalidOverridesDuplicateSignaturesAndGetterCollisionsFailBeforeJavaGeneration() {
        for (String method :
                List.of(
                        "private String toString(){return \"x\";}",
                        "public int toString(){return 1;}",
                        "public String? toString(){return Optional.empty();}",
                        "public void toString(){}",
                        "public String toString(){return \"a\";} public String toString(){return"
                            + " \"b\";}")) {
            var failure =
                    assertThrows(
                            SemanticValidationException.class,
                            () ->
                                    new VernacCompiler()
                                            .compileSource(
                                                    "namespace representation; value Secret(String)"
                                                        + " behavior {"
                                                            + method
                                                            + "};"));
            assertTrue(failure.getMessage().contains("toString()"));
        }
        for (String model :
                List.of(
                        "value Bad(String toString);",
                        "value Bad(String toString) behavior {public String toString(){return"
                            + " \"x\";}};",
                        "value Bad(String) behavior {public int hashCode(){return 0;}};",
                        "id BadId; aggregate Bad[BadId]() behavior {read String toString(){return"
                            + " \"x\";}};"))
            assertThrows(
                    SemanticValidationException.class,
                    () -> new VernacCompiler().compileSource("namespace representation; " + model));
    }

    @Test
    void nullCustomRepresentationFailsWithTheExistingSafeBehaviorContract() throws Exception {
        var result =
                new VernacCompiler()
                        .compileSource(
                                "namespace representation; value Broken(String) behavior {public"
                                    + " String toString(){return null;}};");
        var java = InMemoryJavaCompiler.compile(result);
        assertTrue(java.success(), java.diagnostics().toString());
        var type = java.loadClass("representation.domain.Broken");
        var value = type.getMethod("of", String.class).invoke(null, "seeded-sensitive-value");
        var failure =
                assertThrows(
                        InvocationTargetException.class,
                        () -> type.getMethod("toString").invoke(value));
        assertInstanceOf(BehaviorContractException.class, failure.getCause());
        assertFalse(failure.getCause().toString().contains("seeded-sensitive-value"));
    }

    @Test
    void liveEditorUsesTheSameRepresentationMethodAndRejectsInvalidSignatures() throws Exception {
        Path source = Files.createDirectories(root.resolve("representation"));
        Files.writeString(source.resolve("model.vernac"), MODEL);
        var project = new VernacProjectLoader().load(root);
        var model = new EditorValueObjects().build(project);
        var compiler = new VernacCompiler().compileProject(root);
        String name = "representation.domain.Secret";
        String generated =
                compiler.generatedFiles().stream()
                        .filter(f -> (f.packageName() + "." + f.typeSpec().name()).equals(name))
                        .findFirst()
                        .orElseThrow()
                        .toString();
        assertEquals(generated, model.values().get(name).javaSource());
        assertFalse(generated.contains("encoded='"));
        Files.writeString(
                source.resolve("model.vernac"),
                "namespace representation; value Secret(String) behavior {private String"
                    + " toString(){return \"x\";}};");
        var invalid = new EditorValueObjects().build(new VernacProjectLoader().load(root));
        assertFalse(invalid.values().containsKey(name));
    }
}
