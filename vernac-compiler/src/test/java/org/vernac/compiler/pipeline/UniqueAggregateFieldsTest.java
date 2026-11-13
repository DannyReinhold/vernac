// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.persistence.*;
import org.vernac.compiler.testutil.InMemoryJavaCompiler;
import org.vernac.compiler.tooling.*;

import java.nio.file.*;
import java.util.*;

class UniqueAggregateFieldsTest {
    @TempDir Path root;
    static final String BASE = "id ClaimId; id OwnerId; value LoginKey(String encoded); ";

    ResolvedProject project(String text) throws Exception {
        var file = root.resolve("model/claim.vernac");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "namespace model; " + BASE + text);
        return new VernacCompiler().analyzeProject(root);
    }

    List<FieldNode> fields(ResolvedProject p) {
        return p.project().sources().getFirst().unit().definitions().stream()
                .filter(AggregateNode.class::isInstance)
                .map(AggregateNode.class::cast)
                .findFirst()
                .orElseThrow()
                .fields();
    }

    @Test
    void omittedExplicitAndQualifiedNamesUseTheNormalEffectiveName() throws Exception {
        for (String declaration :
                List.of("unique LoginKey", "unique LoginKey loginKey", "unique model.LoginKey")) {
            var f =
                    fields(project("global aggregate Claim[ClaimId](" + declaration + ");"))
                            .getFirst();
            assertEquals("loginKey", f.name());
            assertTrue(f.isUnique());
            assertFalse(f.isMutable());
            assertEquals(declaration.endsWith(" loginKey"), f.hasExplicitName());
        }
        var f =
                fields(
                                project(
                                        "value URLKey(String); global aggregate"
                                            + " Claim[ClaimId](unique URLKey);"))
                        .getFirst();
        assertEquals("urlKey", f.name());
    }

    @Test
    void independentModifiersAndSupportedNominalTypesRetainDomainMeaning() throws Exception {
        for (String modifier : List.of("unique mut", "mut unique")) {
            var p =
                    project(
                            "value State = OPEN | CLOSED; value Flag(Boolean enabled); value"
                                + " Number(int); value Ref(UUID); tenant aggregate Claim[ClaimId]("
                                    + modifier
                                    + " LoginKey, unique OwnerId, unique State, unique Flag, unique"
                                    + " Number, unique Ref);");
            assertTrue(fields(p).stream().allMatch(FieldNode::isUnique));
            assertTrue(fields(p).getFirst().isMutable());
            assertEquals(6, fields(p).size());
        }
    }

    @Test
    void rejectsUnsupportedEqualityAndPresenceBeforeJavaGeneration() {
        for (String type :
                List.of(
                        "LoginKey?",
                        "OptionalKey",
                        "Nested",
                        "Pair",
                        "Amount",
                        "Moment",
                        "Items",
                        "Part")) {
            String types =
                    "value OptionalKey(String?); value Nested(LoginKey); value Pair(String one,"
                        + " String two); value Amount(BigDecimal); value Moment(Instant); value"
                        + " Item(String) list Items; entity Part[OwnerId](); ";
            var failure =
                    assertThrows(
                            SemanticValidationException.class,
                            () ->
                                    project(
                                            types
                                                    + "aggregate Claim[ClaimId](unique "
                                                    + type
                                                    + ");"));
            assertTrue(
                    failure.getMessage().contains("Unique aggregate field"), failure.getMessage());
            assertTrue(failure.diagnostics().stream().allMatch(d -> d.location().line() > 0));
        }
    }

    @Test
    void rejectsWrongDeclarationKindsAndRepeatedModifiersAtTheirSource() {
        for (String declaration :
                List.of(
                        "value Bad(unique String);",
                        "event Bad(unique LoginKey);",
                        "entity Bad[ClaimId](unique LoginKey);",
                        "port Bad { void call(unique LoginKey); }",
                        "usecase Bad(unique LoginKey) behavior { execute {} }",
                        "service Bad uses unique LoginKey key behavior {}")) {
            var failure =
                    assertThrows(SemanticValidationException.class, () -> project(declaration));
            assertTrue(failure.getMessage().contains("unique belongs only"), failure.getMessage());
        }
        for (String modifier : List.of("unique unique", "mut mut", "unique mut unique")) {
            var failure =
                    assertThrows(
                            SemanticValidationException.class,
                            () -> project("aggregate Claim[ClaimId](" + modifier + " LoginKey);"));
            assertTrue(
                    failure.getMessage().contains("Duplicate field modifier"),
                    failure.getMessage());
        }
    }

    @Test
    void contextualUniqueIdentifiersAndDerivedNameCollisionsKeepTheirContracts() throws Exception {
        var p =
                project(
                        "value unique(String); value Existing(LoginKey unique); aggregate"
                            + " Claim[ClaimId](unique) behavior { read String unique(int ignored)"
                            + " {return \"ok\";} };");
        assertFalse(fields(p).getFirst().isUnique());
        var failure =
                assertThrows(
                        SemanticValidationException.class,
                        () ->
                                project(
                                        "aggregate Claim[ClaimId](unique LoginKey, LoginKey"
                                            + " loginKey);"));
        assertTrue(
                failure.getMessage().toLowerCase(Locale.ROOT).contains("duplicate"),
                failure.getMessage());
    }

    @Test
    void generatedObjectsCompileWithoutInventingAnInMemoryUniquenessRegistry() throws Exception {
        var p = project("global aggregate Claim[ClaimId](unique LoginKey);");
        var generated = new VernacCompiler().generate(p, SchemaModel.empty(), Map.of());
        var compiled = InMemoryJavaCompiler.compile(generated.generatedFiles());
        assertTrue(compiled.success(), compiled.diagnostics().toString());
        var key = compiled.loadClass("model.domain.LoginKey");
        var claim = compiled.loadClass("model.domain.Claim");
        Object value = key.getMethod("of", String.class).invoke(null, "same@example.invalid");
        var create = claim.getMethod("create", key);
        var one = create.invoke(null, value);
        var two = create.invoke(null, value);
        assertNotEquals(one, two);
        assertNotNull(claim.getMethod("loginKey"));
        assertTrue(
                Arrays.stream(claim.getDeclaredMethods())
                        .noneMatch(m -> m.getName().contains("Unique")));
    }

    @Test
    void persistenceAdoptionFailsExplicitlyUntilItsConstraintCanBeEnforced() throws Exception {
        for (String scope : List.of("global", "tenant")) {
            var p =
                    project(
                            scope
                                    + " aggregate Claim[ClaimId](unique LoginKey); repository"
                                    + " Claims for Claim {}");
            var failure =
                    assertThrows(
                            SemanticValidationException.class,
                            () -> new SchemaBuilder(p, SchemaModel.empty()).build());
            assertTrue(failure.getMessage().contains("Persistence enforcement of unique"));
            assertThrows(
                    SemanticValidationException.class,
                    () -> new VernacCompiler().generate(p, SchemaModel.empty(), Map.of()));
        }
    }

    @Test
    void unsavedAnalysisAndNativeTypeReferencesRetainTheDerivedField() throws Exception {
        var file = root.resolve("model/claim.vernac");
        String text =
                "namespace model; " + BASE + "global aggregate Claim[ClaimId](unique LoginKey);";
        var issues = new ArrayList<org.vernac.compiler.analyzer.CompilerDiagnostic>();
        var p = new VernacProjectLoader().loadForAnalysis(root, Map.of(file, text), issues);
        var analyzed = new ProjectTypeResolver().resolveForAnalysis(p);
        assertTrue(issues.isEmpty());
        assertTrue(analyzed.diagnostics().isEmpty());
        assertEquals("loginKey", fields(analyzed).getFirst().name());
        var refs = new VernacTypeReferences().build(p, file, Map.of(file, text));
        int key = text.lastIndexOf("LoginKey");
        assertTrue(
                refs.stream()
                        .anyMatch(r -> r.start() == key && r.identity().equals("model.LoginKey")));
        var invalid = text.replace("unique LoginKey", "unique LoginKey?");
        var changed =
                new VernacProjectLoader()
                        .loadForAnalysis(root, Map.of(file, invalid), new ArrayList<>());
        assertTrue(
                new ProjectTypeResolver()
                        .resolveForAnalysis(changed).diagnostics().stream()
                                .anyMatch(d -> d.message().contains("Unique aggregate field")));
    }
}
