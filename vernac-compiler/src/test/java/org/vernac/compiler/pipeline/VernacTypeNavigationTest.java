// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.tooling.VernacTypeNavigation;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VernacTypeNavigationTest {
    @TempDir Path root;

    private Path source(String namespace, String name, String declarations) throws Exception {
        Path file = root.resolve(namespace.replace('.', '/')).resolve(name + ".vernac");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "namespace " + namespace + ";\n" + declarations);
        return file;
    }
    private Optional<VernacTypeNavigation.Target> find(String javaName, Map<Path, String> overlays) throws Exception {
        var project = new VernacProjectLoader().loadForAnalysis(root, overlays, new ArrayList<>());
        Map<Path, String> texts = new HashMap<>(overlays);
        for (var source : project.sources()) if (!texts.containsKey(source.path()))
            texts.put(source.path(), Files.readString(source.path()));
        return new VernacTypeNavigation().find(project, texts, javaName);
    }
    private Optional<VernacTypeNavigation.Target> member(String owner, String name, List<String> parameters,
                                                         Map<Path, String> overlays) throws Exception {
        var project = new VernacProjectLoader().loadForAnalysis(root, overlays, new ArrayList<>());
        Map<Path, String> texts = new HashMap<>(overlays);
        for (var source : project.sources()) if (!texts.containsKey(source.path()))
            texts.put(source.path(), Files.readString(source.path()));
        return new VernacTypeNavigation().findMember(project, texts, owner, new VernacTypeNavigation.Member(name, parameters));
    }

    private String selected(VernacTypeNavigation.Target target, Map<Path, String> overlays) throws Exception {
        String text = overlays.containsKey(target.source()) ? overlays.get(target.source()) : Files.readString(target.source());
        return text.substring(target.start(), target.end());
    }

    @Test void mapsIdsValuesEnumsAndNamedOrImplicitCollections() throws Exception {
        source("model", "types", """
                id TaskId set;
                value Title(String) list Titles;
                value Status = OPEN | CLOSED;
                """);
        for (String name : List.of("TaskId", "Title", "Titles", "Status"))
            assertEquals(name, selected(find("model.domain." + name, Map.of()).orElseThrow(), Map.of()));
        var implicit = find("model.domain.TaskIds", Map.of()).orElseThrow();
        assertEquals("TaskIds", implicit.name());
        assertEquals("set", selected(implicit, Map.of()));
    }

    @Test void exactJavaIdentityDistinguishesNamespacesAndRejectsCompanions() throws Exception {
        Path first = source("one", "name", "value Name(String);");
        Path second = source("two", "name", "value Name(String);");
        assertEquals(first, find("one.domain.Name", Map.of()).orElseThrow().source());
        assertEquals(second, find("two.domain.Name", Map.of()).orElseThrow().source());
        assertTrue(find("one.Name", Map.of()).isEmpty());
        assertTrue(find("other.domain.Name", Map.of()).isEmpty());
        assertTrue(find("one.domain.__VernacBehavior_Name", Map.of()).isEmpty());
    }

    @Test void liveUnicodeRangesFollowUnsavedEditsAndRenames() throws Exception {
        Path file = source("größe", "name", "value Größe(String);");
        String edited = "// 😀 additional unsaved line\n\nnamespace größe;\nvalue Größe(String);\n";
        var overlay = Map.of(file, edited);
        var target = find("größe.domain.Größe", overlay).orElseThrow();
        assertEquals(edited.indexOf("Größe("), target.start());
        assertEquals("Größe", selected(target, overlay));
        assertTrue(find("größe.domain.Größe", Map.of(file, edited.replace("Größe(", "Neu("))).isEmpty());
    }

    @Test void duplicateOrRemovedDefinitionsHaveNoArbitraryTarget() throws Exception {
        Path first = source("model", "first", "value Name(String);");
        source("model", "second", "value Name(String);");
        assertTrue(find("model.domain.Name", Map.of()).isEmpty());
        Files.delete(first);
        assertTrue(find("model.domain.Name", Map.of()).isPresent());
        Files.delete(root.resolve("model/second.vernac"));
        assertTrue(find("model.domain.Name", Map.of()).isEmpty());
    }

    @Test void unrelatedBrokenFilesDoNotBlockNavigation() throws Exception {
        source("model", "name", "value Name(String);");
        source("model", "broken", "value Broken(");
        assertEquals("Name", find("model.domain.Name", Map.of()).orElseThrow().name());
    }

    @Test void ownershipRequiresAnActualCompilerManifestEntry() throws Exception {
        Path input = root.resolve("input");
        Files.createDirectories(input.resolve("model"));
        Files.writeString(input.resolve("model/types.vernac"), "namespace model; value Name(String);");
        Path output = root.resolve("custom-output");
        new VernacCompiler().compileProject(input).writeTo(output);
        Path generated = output.resolve("model/domain/Name.java");
        assertEquals(output, GeneratedSourceOwnership.outputRootOf(generated).orElseThrow());
        Path manual = output.resolve("model/domain/HandWritten.java");
        Files.writeString(manual, "class HandWritten {}");
        assertTrue(GeneratedSourceOwnership.outputRootOf(manual).isEmpty());
        Files.delete(output.resolve(".vernac-generated-sources"));
        assertTrue(GeneratedSourceOwnership.outputRootOf(generated).isEmpty());
    }

    @Test void malformedOwnershipInventoryIsNeverAccepted() throws Exception {
        Path generated = root.resolve("Name.java");
        Files.writeString(generated, "class Name {}");
        Files.writeString(root.resolve(".vernac-generated-sources"), "vernac-generated-sources-v1\n../Name.java\n");
        assertThrows(java.io.IOException.class, () -> GeneratedSourceOwnership.outputRootOf(generated));
    }
    @Test void gettersUseExplicitNamesOrTheImplicitTypeDeclaration() throws Exception {
        source("model", "types", """
                id TaskId;
                value Title(String);
                value Item(String name, int, String? description);
                """);
        assertEquals("name", selected(member("model.domain.Item", "name", List.of(), Map.of()).orElseThrow(), Map.of()));
        assertEquals("description", selected(member("model.domain.Item", "description", List.of(), Map.of()).orElseThrow(), Map.of()));
        assertEquals("int", selected(member("model.domain.Item", "intValue", List.of(), Map.of()).orElseThrow(), Map.of()));
        assertEquals("String", selected(member("model.domain.Title", "string", List.of(), Map.of()).orElseThrow(), Map.of()));
        assertEquals("TaskId", selected(member("model.domain.TaskId", "value", List.of(), Map.of()).orElseThrow(), Map.of()));
        assertTrue(member("model.domain.Item", "name", List.of("java.lang.String"), Map.of()).isEmpty());
    }

    @Test void overloadedBehaviorUsesExactResolvedParameterTypes() throws Exception {
        Path file = source("model", "name", """
                value Name(String) behavior {
                    public String show(int count) { return self.string(); }
                    public String show(Integer count) { return self.string(); }
                    public String choose(String? candidate) { return self.string(); }
                };
                """);
        String text = Files.readString(file);
        assertEquals(text.indexOf("show(int"), member("model.domain.Name", "show", List.of("int"), Map.of()).orElseThrow().start());
        assertEquals(text.indexOf("show(Integer"), member("model.domain.Name", "show", List.of("java.lang.Integer"), Map.of()).orElseThrow().start());
        assertTrue(member("model.domain.Name", "show", List.of("long"), Map.of()).isEmpty());
        assertTrue(member("model.domain.Name", "show", List.of(), Map.of()).isEmpty());
        assertEquals("choose", selected(member("model.domain.Name", "choose", List.of("java.lang.String"), Map.of()).orElseThrow(), Map.of()));
    }

    @Test void behaviorParametersUseVernacImportsAndQualifiedModelIdentities() throws Exception {
        source("other", "reason", "value Reason(String);");
        source("model", "name", """
                import other.Reason;
                value Name(String) behavior {
                    public String explain(Reason reason) { return self.string(); }
                    public String describe(other.Reason reason) { return self.string(); }
                };
                """);
        assertTrue(member("model.domain.Name", "explain", List.of("other.domain.Reason"), Map.of()).isPresent());
        assertTrue(member("model.domain.Name", "describe", List.of("other.domain.Reason"), Map.of()).isPresent());
        assertTrue(member("model.domain.Name", "explain", List.of("model.domain.Reason"), Map.of()).isEmpty());
    }

    @Test void externalEnumAndCollectionBehaviorNavigateToPublicContract() throws Exception {
        source("model", "types", """
                value Name(String) behavior {
                    public String decorated() implemented by app.NameBehavior;
                    private String suffix() { return "!"; }
                } list Names behavior {
                    public int total() { return self.size(); }
                };
                value Status = OPEN | CLOSED behavior {
                    public boolean open() { return self == Status.OPEN; }
                };
                """);
        assertEquals("decorated", selected(member("model.domain.Name", "decorated", List.of(), Map.of()).orElseThrow(), Map.of()));
        assertEquals("total", selected(member("model.domain.Names", "total", List.of(), Map.of()).orElseThrow(), Map.of()));
        assertEquals("open", selected(member("model.domain.Status", "open", List.of(), Map.of()).orElseThrow(), Map.of()));
        assertTrue(member("model.domain.Name", "suffix", List.of(), Map.of()).isEmpty());
        for (String standard : List.of("size", "stream", "toString", "hashCode"))
            assertTrue(member("model.domain.Names", standard, List.of(), Map.of()).isEmpty());
        assertTrue(member("model.domain.Status", "name", List.of(), Map.of()).isEmpty());
    }

    @Test void liveMemberRenamesAndSignatureChangesNeverChooseOldTargets() throws Exception {
        Path file = source("größe", "name", "value Größe(String text) behavior { public String größer(int count) { return self.text(); } };");
        String edited = "// 😀 unsaved\n" + Files.readString(file);
        var overlay = Map.of(file, edited);
        assertEquals(edited.indexOf("größer(int"), member("größe.domain.Größe", "größer", List.of("int"), overlay).orElseThrow().start());
        assertTrue(member("größe.domain.Größe", "größer", List.of("int"), Map.of(file, edited.replace("int count", "long count"))).isEmpty());
        assertTrue(member("größe.domain.Größe", "text", List.of(), Map.of(file, edited.replace("String text", "String title"))).isEmpty());
    }

    @Test void duplicateMemberSignaturesAndGetterCollisionsDoNotChooseArbitrarily() throws Exception {
        Path file = source("model", "name", """
                value Name(String text) behavior {
                    public String label() { return self.text(); }
                    public String label() { return self.text(); }
                    public String text() { return "collision"; }
                };
                """);
        assertTrue(member("model.domain.Name", "label", List.of(), Map.of()).isEmpty());
        assertTrue(member("model.domain.Name", "text", List.of(), Map.of()).isEmpty());
    }

}
