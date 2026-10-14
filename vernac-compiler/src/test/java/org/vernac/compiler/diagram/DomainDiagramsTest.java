// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.diagram;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.pipeline.*;

import java.nio.file.*;
import java.util.*;

class DomainDiagramsTest {
    @TempDir Path root;

    private void source(String namespace, String body) throws Exception {
        Path p = root.resolve(namespace.replace('.', '/') + "/model.vernac");
        Files.createDirectories(p.getParent());
        Files.writeString(p, "namespace " + namespace + ";" + body);
    }

    @Test
    void exportedModuleTypesKeepTheirDocumentationWithoutExpandingTheirInternals()
            throws Exception {
        Path producer = root.resolve("producer/src/main/vernac");
        Path definition = producer.resolve("lib/values/model.vernac");
        Files.createDirectories(definition.getParent());
        Files.writeString(
                definition,
                "namespace lib.values; term Name {description \"An external customer name.\";}"
                    + " value Name(String);");
        var compiler = new VernacCompiler();
        var project = compiler.analyzeProject(producer);
        var generated =
                compiler.generate(
                        project, org.vernac.compiler.persistence.SchemaModel.empty(), Map.of());
        var module =
                org.vernac.compiler.modules.ModuleContracts.publish(
                        org.vernac.compiler.modules.ModuleDefinition.parse(
                                root.resolve("producer/vernac-module.vmodule"),
                                "module lib.values {export lib.values.Name;}"),
                        project,
                        generated.generatedFiles(),
                        "example",
                        "values",
                        "1",
                        List.of());
        Path consumer = root.resolve("consumer/src/main/vernac");
        Path file = consumer.resolve("app/model.vernac");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "namespace app; import lib.values.Name; value Customer(Name);");
        var diagram =
                DomainDiagrams.model(compiler.analyzeProject(consumer, List.of(module)), "app");
        var boundary =
                diagram.nodes().stream()
                        .filter(n -> n.identity().equals("lib.values.Name"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(boundary.dependency());
        assertEquals("An external customer name.", boundary.description());
        assertTrue(boundary.members().isEmpty());
        assertTrue(
                DomainDiagrams.mermaid(diagram)
                        .contains("lib.values.Name · value_object · module"));
        assertEquals(1, diagram.edges().size());
    }

    @Test
    void resolvedGraphPreservesContainmentCollectionsIdsDependenciesAndBoundaryIdentity()
            throws Exception {
        source("external.values", "value Title(String); value Hidden(String);");
        source(
                "example.delivery",
                """
                import external.values.Title;
                term Tour {description "A planned delivery route.";}
                id TourId; id StopId;
                entity Stop[StopId](Title) list Stops;
                aggregate Tour[TourId](Title, Stops, Stop? preferredStop);
                repository TourRepository for Tour {}
                usecase Inspect(TourId) uses TourRepository behavior {execute {}}
                event Changed(TourId);
                synchronous listener Observe on Changed behavior {execute {}}
                """);
        var resolved = new VernacCompiler().analyzeProject(root);
        var diagram = DomainDiagrams.model(resolved, "example.delivery");
        assertTrue(
                diagram.nodes().stream()
                        .anyMatch(n -> n.identity().equals("external.values.Title")));
        assertFalse(
                diagram.nodes().stream()
                        .anyMatch(n -> n.identity().equals("external.values.Hidden")));
        assertTrue(
                diagram.nodes().stream()
                        .anyMatch(
                                n ->
                                        n.identity().equals("example.delivery.Tour")
                                                && n.description()
                                                        .equals("A planned delivery route.")));
        assertTrue(
                diagram.edges().stream()
                        .anyMatch(
                                e ->
                                        e.from().equals("example.delivery.Tour")
                                                && e.to().equals("example.delivery.Stop")
                                                && e.multiplicity().equals("0..1")));
        assertTrue(
                diagram.edges().stream()
                        .anyMatch(
                                e ->
                                        e.from().equals("example.delivery.Stops")
                                                && e.to().equals("example.delivery.Stop")
                                                && e.multiplicity().equals("0..*")));
        assertTrue(
                diagram.edges().stream()
                        .anyMatch(
                                e ->
                                        e.from().equals("example.delivery.Inspect")
                                                && e.relation().equals("uses")));
        assertFalse(diagram.edges().stream().anyMatch(e -> e.relation().equals("emits")));
        assertFalse(DomainDiagrams.mermaid(diagram).contains("__Vernac"));
        Path output = root.resolve("output");
        DomainDiagrams.writeAll(resolved, output);
        assertTrue(
                Files.readString(output.resolve("example.delivery.md"))
                        .contains("A planned delivery route."));
        String first = Files.readString(output.resolve("example.delivery.json"));
        DomainDiagrams.writeAll(resolved, output);
        assertEquals(first, Files.readString(output.resolve("example.delivery.json")));
        assertFalse(first.contains(root.toString()));
        assertTrue(Files.exists(output.resolve("index.md")));
        assertThrows(
                IllegalArgumentException.class,
                () -> DomainDiagrams.model(resolved, "missing.namespace"));
    }

    @Test
    void renderersEscapeUntrustedLabelsAndKeepAliasesIndependentOfNames() {
        var diagram =
                new DomainDiagrams.Diagram(
                        1,
                        "example",
                        List.of(
                                new DomainDiagrams.Node(
                                        "example.end",
                                        "VALUE_OBJECT",
                                        false,
                                        List.of("x | y"),
                                        "<script>bad</script>"),
                                new DomainDiagrams.Node(
                                        "example.other", "AGGREGATE", false, List.of(), "")),
                        List.of(
                                new DomainDiagrams.Edge(
                                        "example.end", "example.other", "uses", "x\" | y", "1")));
        String rendered = DomainDiagrams.mermaid(diagram);
        assertTrue(rendered.contains("n0[\""));
        assertTrue(rendered.contains("&quot;"));
        assertTrue(rendered.contains("&#124;"));
        assertFalse(DomainDiagrams.markdown(diagram).contains("<script>"));
        assertTrue(DomainDiagrams.plantuml(diagram).endsWith("@enduml\n"));
    }

    @Test
    void anInvalidProjectProducesNoDiagramAndTermOnlyNamespaceIsSupported() throws Exception {
        source("words", "term \"One concept\" {description \"A description.\";}");
        var resolved = new VernacCompiler().analyzeProject(root);
        assertTrue(DomainDiagrams.model(resolved, "words").nodes().isEmpty());
        source("words", "value Invalid(Missing);");
        assertThrows(
                Exception.class,
                () ->
                        DomainDiagrams.writeAll(
                                new VernacCompiler().analyzeProject(root), root.resolve("out")));
        assertFalse(Files.exists(root.resolve("out/index.md")));
    }
}
