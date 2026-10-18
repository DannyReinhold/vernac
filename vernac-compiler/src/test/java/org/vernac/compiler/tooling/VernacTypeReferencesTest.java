// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.tooling;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.modules.*;
import org.vernac.compiler.persistence.SchemaModel;
import org.vernac.compiler.pipeline.*;

import java.nio.file.*;
import java.util.*;

class VernacTypeReferencesTest {
    @TempDir Path root;

    private Path file(String relative, String text) throws Exception {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
        return path;
    }

    private Map<Path, String> texts(VernacProject project) throws Exception {
        Map<Path, String> result = new HashMap<>();
        for (var source : project.sources())
            if (Files.exists(source.path()))
                result.put(source.path(), Files.readString(source.path()));
        return result;
    }

    @Test
    void javaReferencesCoverBuiltinSignaturesAndExplicitImplementationsButNotRawJava() {
        String text =
                """
                // 🧭 supplementary prefix verifies UTF-16 ranges
                namespace example;
                term Label { description "String is prose here."; }
                value Label(String) behavior {
                    public String decorate(String suffix) {
                        String normalized = self.string().strip();
                        return normalized + suffix;
                    }
                    public String externallyDecorated()
                        implemented by example.debug.handwritten.LabelFormatting;
                    public int length() { return self.string().length(); }
                    public Label copied(Label source) { return source; }
                };
                """;
        var refs = new VernacTypeReferences().javaReferences(text);
        assertEquals(5, refs.size());
        for (var ref : refs.subList(0, 4)) {
            assertEquals("java.lang.String", ref.javaName());
            assertEquals("String", text.substring(ref.start(), ref.end()));
        }
        var implementation = refs.getLast();
        assertEquals("example.debug.handwritten.LabelFormatting", implementation.javaName());
        assertEquals(
                "LabelFormatting", text.substring(implementation.start(), implementation.end()));
        assertFalse(refs.stream().anyMatch(r -> r.start() == text.indexOf("String normalized")));
        assertFalse(refs.stream().anyMatch(r -> r.javaName().equals("Label")));
    }

    @Test
    void javaReferencesRespectBuiltinIdentityAndDoNotGuessUnknownOrPrimitiveClasses() {
        String text =
                """
                namespace example;
                value Data(BigDecimal? amount, int count, Missing missing, example.Unknown unknown);
                value Kind = String | Integer;
                """;
        var refs = new VernacTypeReferences().javaReferences(text);
        assertEquals(1, refs.size());
        assertEquals("java.math.BigDecimal", refs.getFirst().javaName());
        assertEquals("BigDecimal", text.substring(refs.getFirst().start(), refs.getFirst().end()));
        assertTrue(
                new VernacTypeReferences()
                        .javaReferences("namespace example; value Broken(")
                        .isEmpty());
    }

    @Test
    void termLinksOnlyItsExactLocalModelIncludingQuotedNames() throws Exception {
        Path path =
                file(
                        "example/model.vernac",
                        """
                        namespace example;
                        term Label { description "A label."; }
                        value Label(String);
                        term "Labels" { description "Labels."; }
                        term "Outstanding balance" { description "A standalone concept."; }
                        term Missing { description "No type."; }
                        term "other.Label" { description "Not a qualified reference."; }
                        value Other(String) list Labels;
                        """);
        var project = new VernacProjectLoader().load(root);
        var snapshots = texts(project);
        var refs = new VernacTypeReferences().build(project, path, snapshots);
        assertEquals(2, refs.size());
        String text = snapshots.get(path);
        assertEquals("Label", text.substring(refs.get(0).start(), refs.get(0).end()));
        assertEquals("example.Label", refs.get(0).identity());
        assertEquals("\"Labels\"", text.substring(refs.get(1).start(), refs.get(1).end()));
        assertEquals("example.Labels", refs.get(1).identity());
        assertEquals("Labels", refs.get(1).target().name());
    }

    @Test
    void linksExactTypeRangesImportsCollectionsAndTermsWithoutTouchingJavaOrEnumConstants()
            throws Exception {
        Path library =
                file(
                        "example/values/model.vernac",
                        """
                        namespace example.values;
                        term Label { description "A human-readable parcel label."; }
                        value Label(String) list;
                        """);
        Path path =
                file(
                        "example/client/model.vernac",
                        """
                        namespace example.client;
                        import example.values.Label;
                        import example.values.Labels;
                        value Pair(Label first, example.values.Label second, Labels);
                        value Mode = Label | String;
                        value Host(String) behavior {
                            java imports { java.util.Locale; }
                            public String show() { return "Label".toUpperCase(Locale.ROOT); }
                        };
                        """);
        var project = new VernacProjectLoader().load(root);
        var texts = texts(project);
        var refs = new VernacTypeReferences().build(project, path, texts);
        assertEquals(5, refs.size());
        String text = texts.get(path);
        for (var ref : refs) {
            assertEquals(
                    ref.identity().substring(ref.identity().lastIndexOf('.') + 1),
                    text.substring(ref.start(), ref.end()));
            assertEquals(library, ref.target().source());
            assertTrue(ref.end() <= text.indexOf("value Mode"));
            String declaration =
                    texts.get(library).substring(ref.target().start(), ref.target().end());
            assertEquals(ref.identity().endsWith("Labels") ? "list" : "Label", declaration);
        }
        // Same locator is also used by Java -> Vernac navigation, including files with terms.
        assertEquals(
                "Label",
                new VernacTypeNavigation()
                        .find(project, texts, "example.values.domain.Label")
                        .orElseThrow()
                        .name());
    }

    @Test
    void sameNamespaceAndLiveUnicodeOffsetsSurviveAnUnrelatedBrokenFile() throws Exception {
        Path declaration =
                file(
                        "example/über/name.vernac",
                        "namespace example.über; // 🧭\r\n"
                                + "term ÜberName {description \"Name\";}\r\n"
                                + "value ÜberName(String);\r\n"
                                + "value 𐐀Name(String);");
        Path path = file("example/über/use.vernac", "namespace example.über; value Use(ÜberName);");
        file("example/über/broken.vernac", "namespace example.über; value Broken(");
        String snapshot =
                "namespace example.über; // 🧭\r\nvalue Use(ÜberName first, 𐐀Name second);";
        var project =
                new VernacProjectLoader()
                        .loadForAnalysis(root, Map.of(path, snapshot), new ArrayList<>());
        var texts = texts(project);
        texts.put(path, snapshot);
        var refs = new VernacTypeReferences().build(project, path, texts);
        assertEquals(2, refs.size());
        assertEquals(snapshot.indexOf("ÜberName"), refs.getFirst().start());
        assertEquals("𐐀Name", snapshot.substring(refs.getLast().start(), refs.getLast().end()));
        assertEquals(declaration, refs.getFirst().target().source());
        assertTrue(refs.getFirst().target().start() > texts.get(declaration).indexOf("value"));
    }

    @Test
    void ambiguityDoesNotInventALinkAndQualifiedReferencesRemainAvailable() throws Exception {
        file("example/one/model.vernac", "namespace example.one; value Name(String);");
        file("example/two/model.vernac", "namespace example.two; value Name(String);");
        Path path =
                file(
                        "example/client/model.vernac",
                        "namespace example.client; import example.one.*; import example.two.*;"
                            + " value Use(Name first, example.one.Name second, Missing third);");
        var project = new VernacProjectLoader().load(root);
        var refs = new VernacTypeReferences().build(project, path, texts(project));
        assertEquals(1, refs.size());
        assertEquals("example.one.Name", refs.getFirst().identity());
    }

    @Test
    void includesRepositoryAggregateIdsAndUsecaseDependencies() throws Exception {
        Path path =
                file(
                        "example/model.vernac",
                        """
                        namespace example; id RootId; value Name(String);
                        aggregate Root[RootId](Name) list Roots;
                        repository for Root { find Roots all(); }
                        usecase Read(RootId) uses RootRepository behavior { execute {} }
                        """);
        var project = new VernacProjectLoader().load(root);
        var refs = new VernacTypeReferences().build(project, path, texts(project));
        assertEquals(
                Set.of(
                        "example.Root",
                        "example.RootId",
                        "example.Name",
                        "example.Roots",
                        "example.RootRepository"),
                new HashSet<>(
                        refs.stream().map(VernacTypeReferences.Reference::identity).toList()));
        assertEquals(
                "RootRepository",
                refs.stream()
                        .filter(r -> r.identity().endsWith("RootRepository"))
                        .findFirst()
                        .orElseThrow()
                        .target()
                        .name());
    }

    @Test
    void moduleExportsResolveToContractsWhileInternalTypesStayHidden() throws Exception {
        Path producer = root.resolve("producer");
        Files.createDirectories(producer.resolve("library/types"));
        Files.writeString(
                producer.resolve("library/types/model.vernac"),
                "namespace library.types; term Name { description \"Customer name\"; } value"
                        + " Name(String); value Hidden(String);");
        var compiler = new VernacCompiler();
        var resolved = compiler.analyzeProject(producer);
        var generated = compiler.generate(resolved, SchemaModel.empty(), Map.of());
        var module =
                ModuleContracts.publish(
                        ModuleDefinition.parse(
                                root.resolve("producer/vernac-module.vmodule"),
                                "module library.values { export library.types.Name; }"),
                        resolved,
                        generated.generatedFiles(),
                        "example",
                        "values",
                        "1",
                        List.of());
        Path consumer = root.resolve("consumer"), path = consumer.resolve("example/use.vernac");
        Files.createDirectories(path.getParent());
        Files.writeString(
                path,
                "namespace example; import library.types.Name; value Use(Name, library.types.Hidden"
                        + " hidden);");
        var project =
                ModuleArtifacts.merge(new VernacProjectLoader().load(consumer), List.of(module));
        var texts = texts(project);
        for (var source : project.sources())
            if (source.external())
                texts.put(source.path(), ModuleArtifacts.view(module, source.unit().namespace()));
        var refs = new VernacTypeReferences().build(project, path, texts);
        assertEquals(2, refs.size());
        assertTrue(refs.stream().allMatch(r -> r.identity().equals("library.types.Name")));
        assertTrue(
                refs.getFirst().target().source().toString().contains("vernac-module-contracts"));
    }
}
