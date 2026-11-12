// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.authorization;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.modules.*;
import org.vernac.compiler.ast.SourceLocation;
import org.vernac.compiler.analyzer.SemanticValidationException;

class AuthorizationLanguageTest {
    @TempDir Path root;
    private static final String PREFIX = "namespace sample; id InvoiceId; value Label(String); permission InvoicePermissions { VIEW; EXPORT; } ";
    private static String code(String model) {
        return new VernacCompiler().compileSource(model).generatedFiles().stream().map(Object::toString).reduce("", String::concat);
    }
    private static void rejects(String declaration, String message) {
        var error = assertThrows(SemanticValidationException.class, () -> code(PREFIX + declaration));
        assertTrue(error.getMessage().contains(message), error.getMessage());
    }
    @Test void emitsTechnicalPermissionEnumsAndNominalGuardBeforeValidationAndImplementation() {
        String generated = code(PREFIX + """
            usecase ExportInvoice(InvoiceId) returns Label
                requires all {
                    permission InvoicePermissions.VIEW on invoiceId;
                    any {
                        permission InvoicePermissions.EXPORT on invoiceId;
                        permission InvoicePermissions.VIEW;
                    }
                }
            validates { require(self.invoiceId() != null, "required"); }
            behavior { execute { return Label.of("ok"); } }
            """);
        assertTrue(generated.contains("package sample.authorization;"));
        assertTrue(generated.contains("implements PermissionKey")); assertTrue(generated.contains("sample.InvoicePermissions."));
        assertTrue(generated.contains("ResourceReference.of(InvoiceId.class, invoiceId)"));
        assertTrue(generated.contains("AuthorizationRequirement.any("));
        int nullCheck = generated.indexOf("DomainChecks.requireNonNull(invoiceId");
        int guard = generated.indexOf("this.__vernacAuthorization.enter");
        int validation = generated.indexOf("__VernacValidation_ExportInvoice.validate");
        assertTrue(nullCheck >= 0 && nullCheck < guard && guard < validation);
        assertTrue(generated.contains("Propagation.REQUIRED"));
    }
    @Test void omittedClauseKeepsOldConstructorAndPublicHasOnlyAnOptionalGuardProvider() {
        String legacy = code("namespace sample; usecase Open() behavior {execute {}}");
        assertFalse(legacy.contains("AuthorizationGuard")); assertTrue(legacy.contains("Propagation.REQUIRED"));
        String open = code("namespace sample; usecase Open() requires public behavior {execute {}}");
        assertTrue(open.contains("ObjectProvider<AuthorizationGuard>"));
        assertTrue(open.contains("__vernacGuard == null ? null"));
        assertTrue(open.contains("AuthorizationRequirement.Mode.PUBLIC"));
        assertTrue(code("namespace sample; usecase Open() requires authenticated behavior {execute {}}").contains("AuthorizationRequirement.Mode.AUTHENTICATED"));
    }
    @Test void rejectsUnknownNonPermissionAndUnknownMemberReferences() {
        rejects("usecase Read() requires permission Missing.VIEW behavior {execute {}}", "Missing");
        rejects("usecase Read() requires permission Label.VIEW behavior {execute {}}", "expects a permission declaration");
        rejects("usecase Read() requires permission InvoicePermissions.UNKNOWN behavior {execute {}}", "Unknown permission member");
        rejects("usecase Read() requires permission InvoicePermissions behavior {execute {}}", "declaration and member");
    }
    @Test void rejectsEmptyAndCanonicalDuplicateGroups() {
        rejects("usecase Read() requires all {} behavior {execute {}}", "must not be empty");
        rejects("usecase Read() requires any {} behavior {execute {}}", "must not be empty");
        rejects("usecase Read() requires all {permission InvoicePermissions.VIEW; permission sample.InvoicePermissions.VIEW;} behavior {execute {}}", "Duplicate sibling");
    }
    @Test void rejectsOptionalCollectionAndUnknownResourceInputs() {
        rejects("usecase Read(InvoiceId? invoiceId) requires permission InvoicePermissions.VIEW on invoiceId behavior {execute {}}", "required ID or value-object");
        rejects("usecase Read(InvoiceId) requires permission InvoicePermissions.VIEW on other behavior {execute {}}", "Unknown authorization resource input");
        rejects("id OtherId list OtherIds; usecase Read(OtherIds) requires permission InvoicePermissions.VIEW on otherIds behavior {execute {}}", "required ID or value-object");
        assertTrue(code(PREFIX + "usecase Read(Label) requires permission InvoicePermissions.VIEW on label behavior {execute {}}").contains("ResourceReference.of(Label.class, label)"));
    }
    @Test void parserRejectsModesInGroupsExpressionsAndWrongClauseOrder() {
        for (String clause : List.of("all {authenticated;}", "any {public;}",
                "permission InvoicePermissions.VIEW on invoiceId.value()", "not permission InvoicePermissions.VIEW")) {
            rejects("usecase Read(InvoiceId) requires " + clause + " behavior {execute {}}", "Syntax error");
        }
        rejects("usecase Read() uses Read read requires authenticated behavior {execute {}}", "Syntax error");
    }
    @Test void permissionDeclarationsAreNonemptyDistinctAndSeparateFromDomainEnums() {
        assertThrows(SemanticValidationException.class, () -> code("namespace sample; permission Empty {}"));
        assertThrows(SemanticValidationException.class, () -> code("namespace sample; permission P {VIEW;VIEW;}"));
        rejects("value Bad(InvoicePermissions);", "cannot use permission");
        rejects("usecase Bad(InvoicePermissions) behavior {execute {}}", "inputs/results require");
        rejects("id InvoicePermissions;", "Duplicate type declaration");
        String names = code("namespace sample; permission P {P; String; name; values; qualifiedName;}");
        assertTrue(names.contains("qualifiedName()"));
    }
    @Test void localImportsResolveBeforeGenerationWithoutChangingQualifiedIdentities() throws Exception {
        write("security/rights.vernac", "namespace security; permission InvoicePermissions {VIEW;}");
        write("billing/read.vernac", "namespace billing; import security.InvoicePermissions; id InvoiceId; usecase Read(InvoiceId) requires permission InvoicePermissions.VIEW on invoiceId behavior {execute {}}");
        var result = new VernacCompiler().compileProject(root);
        String generated = result.generatedFiles().stream().map(Object::toString).reduce("", String::concat);
        assertTrue(generated.contains("security.authorization.InvoicePermissions")); assertTrue(generated.contains("security.InvoicePermissions."));
    }
    @Test void modulePublicationRetainsPermissionAndPublicAccessContracts() throws Exception {
        for (String declarations : List.of("permission InvoicePermissions {VIEW;}", "usecase Read() requires public behavior {execute {}}")) {
            write("sample/model.vernac", "namespace sample; " + declarations);
            var compiler = new VernacCompiler(); var resolved = compiler.analyzeProject(root);
            var generated = compiler.compileProject(root);
            var definition = new ModuleDefinition("sample.library", List.of());
            var descriptor = ModuleContracts.publish(definition, resolved, generated.generatedFiles(), "sample", "library", "1.0", List.of());
            assertDoesNotThrow(() -> ModuleContracts.checkDependencies(List.of(descriptor)));
            if (declarations.startsWith("usecase"))
                assertEquals("PUBLIC", descriptor.usecaseAccess().getFirst().requirement().mode());
            else assertTrue(descriptor.internalTypes().contains("sample.InvoicePermissions"));
        }
    }
    @Test void newSoftKeywordsRemainUsableAsFieldAndMethodNames() {
        assertTrue(code("namespace sample; value Label(String permission, String requires, String authenticated, String all, String any) behavior {public String permission(String requires) {return self.all();}}").contains("String permission(String requires)"));
    }
    @Test void nativeAndJavaNavigationLocatePermissionDeclarationsAndMembers() throws Exception {
        String text = "namespace sample; permission Rights {VIEW;} id InvoiceId; usecase Read(InvoiceId) requires permission Rights.VIEW on invoiceId behavior {execute {}}";
        write("sample/model.vernac", text);
        var project = new VernacCompiler().readProject(root);
        Path source = root.resolve("sample/model.vernac"); var texts = Map.of(source, text);
        var navigation = new org.vernac.compiler.tooling.VernacTypeNavigation();
        var declaration = navigation.find(project, texts, "sample.authorization.Rights").orElseThrow();
        assertEquals("Rights", text.substring(declaration.start(), declaration.end()));
        var member = navigation.findMember(project, texts, "sample.authorization.Rights",
                new org.vernac.compiler.tooling.VernacTypeNavigation.Member("VIEW", List.of(), true)).orElseThrow();
        assertEquals("VIEW", text.substring(member.start(), member.end()));
        var references = new org.vernac.compiler.tooling.VernacTypeReferences().build(project, source, texts);
        assertTrue(references.stream().anyMatch(r -> r.identity().equals("sample.Rights") && r.start() == text.lastIndexOf("Rights.VIEW")));
    }
    private void write(String path, String content) throws Exception { Path file=root.resolve(path); Files.createDirectories(file.getParent()); Files.writeString(file,content); }
}
