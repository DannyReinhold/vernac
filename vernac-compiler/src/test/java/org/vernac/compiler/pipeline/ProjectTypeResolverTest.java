// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.ast.TypeNode;
import org.vernac.compiler.symbols.ResolvedType;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class ProjectTypeResolverTest {
    @TempDir Path root;
    private final VernacCompiler compiler = new VernacCompiler();

    private Path source(String namespace, String file, String body) throws IOException {
        Path path = root.resolve(namespace.replace('.', '/')).resolve(file + ".vernac");
        Files.createDirectories(path.getParent());
        return Files.writeString(path, "namespace " + namespace + ";\n" + body);
    }

    private ResolvedProject analyze() throws IOException {
        return compiler.analyzeProject(root);
    }

    private TypeNode reference(ResolvedProject project, String namespace, String value) {
        return project.project().sources().stream().filter(s -> s.unit().namespace().equals(namespace))
                .flatMap(s -> s.unit().valueObjects().stream()).filter(vo -> vo.name().equals(value))
                .findFirst().orElseThrow().fields().getFirst().type();
    }

    private String target(ResolvedProject project, String namespace, String value) {
        var type = assertInstanceOf(ResolvedType.Declared.class, project.typeOf(reference(project, namespace, value)));
        return type.symbol().identity().qualifiedName();
    }

    private SemanticValidationException failure(String message) {
        var error = assertThrows(SemanticValidationException.class, this::analyze);
        assertTrue(error.getMessage().contains(message), error.getMessage());
        return error;
    }

    @Test
    void resolvesSameFileForwardReferencesAndSameNamespaceReferencesAcrossFiles() throws IOException {
        source("org.tasks", "a-model", "value Label(Title title); value Pair(Label label); ");
        source("org.tasks", "z-values", "value Title(String value);");
        var project = analyze();
        assertEquals("org.tasks.Title", target(project, "org.tasks", "Label"));
        assertEquals("org.tasks.Label", target(project, "org.tasks", "Pair"));
    }

    @Test
    void resolvesExplicitImportsToVernacIdentities() throws IOException {
        source("org.shared", "types", "id OwnerId;");
        source("org.tasks", "model", "import org.shared.OwnerId; value Owner(OwnerId id);");
        assertEquals("org.shared.OwnerId", target(analyze(), "org.tasks", "Owner"));
    }

    @Test
    void resolvesWildcardImports() throws IOException {
        source("org.shared", "types", "value Title(String value);");
        source("org.tasks", "model", "import org.shared.*; value Label(Title title);");
        assertEquals("org.shared.Title", target(analyze(), "org.tasks", "Label"));
    }

    @Test
    void resolvesFullyQualifiedVernacReferencesWithoutImports() throws IOException {
        source("org.custom", "types", "value Title(String value);");
        source("org.tasks", "model", "value Label(org.custom.Title title);");
        assertEquals("org.custom.Title", target(analyze(), "org.tasks", "Label"));
    }

    @Test
    void localDeclarationsTakePrecedenceOverWildcards() throws IOException {
        source("org.shared", "types", "value Title(String value);");
        source("org.tasks", "local", "value Title(String value);");
        source("org.tasks", "model", "import org.shared.*; value Label(Title title);");
        assertEquals("org.tasks.Title", target(analyze(), "org.tasks", "Label"));
    }

    @Test
    void explicitImportDisambiguatesWildcardsAndQualifiedReferencesRemainAvailable() throws IOException {
        source("org.billing", "types", "value Address(String value);");
        source("org.shipping", "types", "value Address(String value);");
        source("org.tasks", "model", """
                import org.billing.*;
                import org.shipping.*;
                import org.billing.Address;
                value Billing(Address address);
                value Shipping(org.shipping.Address address);
                """);
        var project = analyze();
        assertEquals("org.billing.Address", target(project, "org.tasks", "Billing"));
        assertEquals("org.shipping.Address", target(project, "org.tasks", "Shipping"));
    }

    @Test
    void reportsWildcardAmbiguityAtTheUseSiteWithBothCandidates() throws IOException {
        source("org.billing", "types", "value Address(String value);");
        source("org.shipping", "types", "value Address(String value);");
        Path consumer = source("org.tasks", "model", "import org.shipping.*;\nimport org.billing.*;\nvalue Home(Address address);");
        var error = failure("Ambiguous type 'Address'");
        assertEquals(consumer.toString(), error.diagnostics().getFirst().location().sourceName());
        assertEquals(4, error.diagnostics().getFirst().location().line());
        assertTrue(error.getMessage().contains("org.billing.Address"));
        assertTrue(error.getMessage().contains("org.shipping.Address"));
        assertTrue(error.getMessage().contains("Add an explicit import"));
    }

    @Test
    void unusedWildcardOverlapIsNotAnError() throws IOException {
        source("org.billing", "types", "value Address(String value);");
        source("org.shipping", "types", "value Address(String value);");
        source("org.tasks", "model", "import org.billing.*; import org.shipping.*; value Name(String value);");
        assertTrue(analyze().diagnostics().isEmpty());
    }

    @Test
    void rejectsUnknownExplicitImportsEvenWhenUnused() throws IOException {
        Path file = source("org.tasks", "model", "import org.missing.Title;\nvalue Label(String value);");
        var error = failure("Cannot import 'org.missing.Title'");
        assertEquals(file.toString(), error.diagnostics().getFirst().location().sourceName());
        assertEquals(2, error.diagnostics().getFirst().location().line());
    }

    @Test
    void rejectsUnknownWildcardNamespacesEvenWhenUnused() throws IOException {
        source("org.tasks", "model", "import org.missing.*; value Label(String value);");
        failure("Unknown Vernac namespace 'org.missing'");
    }

    @Test
    void anEmptyDeclaredNamespaceIsStillKnown() throws IOException {
        source("org.shared", "empty", "");
        source("org.tasks", "model", "import org.shared.*; value Label(String value);");
        assertTrue(analyze().diagnostics().isEmpty());
    }

    @Test
    void wildcardDoesNotIncludeChildNamespaces() throws IOException {
        source("org.shared", "empty", "");
        source("org.shared.child", "types", "value Title(String value);");
        source("org.tasks", "model", "import org.shared.*; value Label(Title title);");
        failure("Unknown type 'Title'");
    }

    @Test
    void rejectsOwnNamespaceWildcard() throws IOException {
        source("org.tasks", "model", "import org.tasks.*; id TaskId;");
        failure("Cannot import the current namespace 'org.tasks'");
    }

    @Test
    void rejectsOwnNamespaceExplicitImportEvenWhenDeclaredInAnotherFile() throws IOException {
        source("org.tasks", "ids", "id TaskId;");
        source("org.tasks", "model", "import org.tasks.TaskId; value Label(String value);");
        failure("Its types are already available. Remove this import.");
    }

    @Test
    void importingAChildNamespaceIsNotASelfImport() throws IOException {
        source("org.tasks.history", "types", "value Title(String value);");
        source("org.tasks", "model", "import org.tasks.history.Title; value Label(Title title);");
        assertEquals("org.tasks.history.Title", target(analyze(), "org.tasks", "Label"));
    }

    @Test
    void rejectsLocalExplicitImportConflictsEvenWhenUnused() throws IOException {
        source("org.shared", "types", "value Title(String value);");
        source("org.tasks", "types", "value Title(String value);");
        source("org.tasks", "model", "import org.shared.Title; value Label(String value);");
        failure("conflicts with local type 'org.tasks.Title'");
    }

    @Test
    void rejectsTwoConflictingExplicitImportsEvenWhenUnused() throws IOException {
        source("org.billing", "types", "value Address(String value);");
        source("org.shipping", "types", "value Address(String value);");
        source("org.tasks", "model", "import org.billing.Address;\nimport org.shipping.Address;\nvalue Label(String value);");
        var error = failure("Conflicting explicit imports for 'Address'");
        assertEquals(3, error.diagnostics().getFirst().location().line());
    }

    @Test
    void repeatedImportsProduceWarningsWithoutFalseAmbiguity() throws IOException {
        source("org.shared", "types", "value Title(String value);");
        source("org.tasks", "model", """
                import org.shared.Title;
                import org.shared.Title;
                import org.shared.*;
                import org.shared.*;
                value Label(Title title);
                """);
        var project = analyze();
        assertEquals("org.shared.Title", target(project, "org.tasks", "Label"));
        assertEquals(2, project.diagnostics().size());
        assertTrue(project.diagnostics().stream().allMatch(d -> d.severity() == CompilerDiagnostic.Severity.WARNING));
        assertEquals(3, project.diagnostics().getFirst().location().line());
    }

    @Test
    void importsDoNotLeakBetweenFilesOfTheSameNamespace() throws IOException {
        source("org.shared", "types", "value Title(String value);");
        source("org.tasks", "a", "import org.shared.Title; value First(Title title);");
        Path second = source("org.tasks", "b", "value Second(Title title);");
        var error = failure("Unknown type 'Title'");
        assertEquals(second.toString(), error.diagnostics().getFirst().location().sourceName());
    }

    @Test
    void importingATypeDoesNotReexportItsImports() throws IOException {
        source("org.internal", "types", "value Detail(String value);");
        source("org.facade", "types", "import org.internal.Detail; value Envelope(Detail detail);");
        source("org.tasks", "model", "import org.facade.*; value Label(Detail detail);");
        failure("Unknown type 'Detail'");
    }

    @Test
    void namespaceCyclesResolveWithoutRecursiveImportExpansion() throws IOException {
        source("org.first", "types", "import org.second.*; value First(Second? second);");
        source("org.second", "types", "import org.first.*; value Second(First? first);");
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            var project = analyze();
            assertEquals("org.second.Second", target(project, "org.first", "First"));
            assertEquals("org.first.First", target(project, "org.second", "Second"));
        });
    }

    @Test
    void neverTreatsFullyQualifiedJavaNamesAsVernacTypes() throws IOException {
        source("org.tasks", "model", "value Label(org.example.MyJavaClass value);");
        failure("Unknown Vernac type 'org.example.MyJavaClass'");
    }

    @Test
    void generatedJavaPackageNamesAreNotVernacAliases() throws IOException {
        source("org.shared", "types", "value Title(String value);");
        source("org.tasks", "model", "value Label(org.shared.domain.Title title);");
        failure("Unknown Vernac type 'org.shared.domain.Title'");
    }

    @Test
    void fileLevelJavaImportsAreRejected() throws IOException {
        source("org.tasks", "model", "import java.math.BigDecimal; value Price(BigDecimal amount);");
        failure("File-level imports do not import Java classes");
    }

    @Test
    void builtinsResolveWithoutImportsAndOptionalityRemainsExplicit() throws IOException {
        source("org.tasks", "model", "value Price(BigDecimal? amount); value Count(int value); value Code(String value);");
        var project = analyze();
        var price = reference(project, "org.tasks", "Price");
        assertTrue(price.isOptional());
        assertEquals(java.math.BigDecimal.class, assertInstanceOf(ResolvedType.Builtin.class, project.typeOf(price)).javaType());
        assertEquals(int.class, assertInstanceOf(ResolvedType.Builtin.class,
                project.typeOf(reference(project, "org.tasks", "Count"))).javaType());
        assertThrows(UnsupportedOperationException.class, () -> project.types().clear());
    }

    @Test
    void idsAndEnumsAreValidValueObjectFields() throws IOException {
        source("org.tasks", "model", "id TaskId; value State = PENDING | DONE; value Key(TaskId id); value Phase(State state);");
        var project = analyze();
        assertEquals("org.tasks.TaskId", target(project, "org.tasks", "Key"));
        assertEquals("org.tasks.State", target(project, "org.tasks", "Phase"));
    }

    @Test
    void rejectsOptionalPrimitivesWithActionableWrapperHints() throws IOException {
        for (String primitive : new String[]{"boolean", "byte", "short", "int", "long", "float", "double", "char"}) {
            source("org.tasks", "model", "value Sample(" + primitive + "? value);");
            failure("Primitive type '" + primitive + "' cannot be optional. Use '");
        }
    }

    @Test
    void rejectsFieldlessValueObjects() throws IOException {
        source("org.tasks", "model", "value Empty();");
        failure("Value Object 'Empty' must declare at least one field");
    }

    @Test
    void rejectsMutableValueObjectFields() throws IOException {
        source("org.tasks", "model", "value Name(mut String value);");
        failure("Value Object 'Name' cannot have mutable field 'value'");
    }

    @Test
    void rejectsEntitiesAsValueObjectFieldsButKeepsTheirDeclarationsIndexed() throws IOException {
        source("org.tasks", "model", "id TaskId; entity Task[TaskId](String title); value Wrapper(Task task);");
        failure("cannot use entity type 'org.tasks.Task'");
    }

    @Test
    void rejectsRawGenericFieldTypes() throws IOException {
        source("org.tasks", "model", "value Names(List<String> names);");
        failure("Generic field type 'List' is not supported in a Value Object");
    }

    @Test
    void resolvesValueCollectionFields() throws IOException {
        source("org.tasks", "model", "value Tag(String value) list Tags; value Labels(Tags tags);");
        assertDoesNotThrow(this::analyze);
    }

    @Test
    void reportsDeferredDeclarationKindsExplicitly() throws IOException {
        source("org.tasks", "model", "id TaskId; entity Task[TaskId](NotReviewedYet title); value Name(String value);");
        var project = analyze();
        assertEquals(1, project.deferredTypes().size());
        assertEquals("org.tasks.Task", project.deferredTypes().getFirst().identity().qualifiedName());
    }
}
