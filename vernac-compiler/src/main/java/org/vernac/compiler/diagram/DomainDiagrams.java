// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.diagram;

import org.vernac.compiler.analyzer.*;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.symbols.*;
import org.vernac.compiler.vocabulary.Vocabulary;

import java.nio.file.*;
import java.util.*;

/** Deterministic, data-only domain graph; no generated Java or embedded-body analysis. */
public final class DomainDiagrams {
    private DomainDiagrams() {}

    public record Node(
            String identity,
            String kind,
            boolean dependency,
            List<String> members,
            String description) {
        public Node {
            members = List.copyOf(members);
        }
    }

    public record Edge(String from, String to, String relation, String role, String multiplicity) {}

    public record Diagram(int formatVersion, String namespace, List<Node> nodes, List<Edge> edges) {
        public Diagram {
            nodes = List.copyOf(nodes);
            edges = List.copyOf(edges);
        }
    }

    public static List<String> namespaces(ResolvedProject project) {
        return project.project().sources().stream()
                .filter(s -> !s.external())
                .map(s -> s.unit().namespace())
                .distinct()
                .sorted()
                .toList();
    }

    public static Diagram model(ResolvedProject project, String namespace) {
        var errors =
                project.diagnostics().stream()
                        .filter(d -> d.severity() == CompilerDiagnostic.Severity.ERROR)
                        .toList();
        if (!errors.isEmpty()) throw new SemanticValidationException(errors);
        if (!namespaces(project).contains(namespace))
            throw new IllegalArgumentException(
                    "No local Vernac namespace '"
                            + namespace
                            + "'. Available: "
                            + namespaces(project));
        var builder = new Builder(project, namespace);
        for (var source : project.project().sources())
            if (!source.external() && source.unit().namespace().equals(namespace)) {
                for (var symbol : project.project().symbols().inNamespace(namespace))
                    if (symbol.sourceFile().equals(source.path())) builder.node(symbol);
                for (var definition : source.unit().definitions())
                    builder.definition(source, definition);
            }
        return new Diagram(
                1,
                namespace,
                List.copyOf(builder.nodes.values()),
                builder.edges.stream()
                        .sorted(
                                Comparator.comparing(Edge::from)
                                        .thenComparing(Edge::to)
                                        .thenComparing(Edge::relation)
                                        .thenComparing(Edge::role))
                        .toList());
    }

    private static final class Builder {
        private final ResolvedProject project;
        private final Map<String, Node> nodes = new TreeMap<>();
        private final Set<Edge> edges = new HashSet<>();
        private final Map<String, Vocabulary.Entry> words = new HashMap<>();

        Builder(ResolvedProject project, String namespace) {
            this.project = project;
            for (var t : Vocabulary.entries(project.project()))
                if (t.describes(t.qualifiedName())) words.put(t.qualifiedName(), t);
        }

        void node(TypeSymbol symbol) {
            String name = symbol.identity().qualifiedName();
            if (nodes.containsKey(name)) return;
            boolean dependency =
                    project.project().sources().stream()
                            .anyMatch(s -> s.external() && s.path().equals(symbol.sourceFile()));
            var term = words.get(name);
            nodes.put(
                    name,
                    new Node(
                            name,
                            symbol.kind().name(),
                            dependency,
                            List.of(),
                            term == null ? "" : term.description()));
        }

        void members(String identity, List<String> values) {
            var node = nodes.get(identity);
            if (node != null)
                nodes.put(
                        identity,
                        new Node(
                                identity,
                                node.kind(),
                                node.dependency(),
                                values,
                                node.description()));
        }

        void reference(
                VernacSourceFile source,
                String owner,
                TypeNode type,
                String relation,
                String role,
                String cardinality) {
            var resolved = project.types().get(type);
            if (resolved == null)
                resolved =
                        project.scopes()
                                .get(source.path())
                                .resolve(type.name(), type.location())
                                .type()
                                .orElse(null);
            TypeSymbol target =
                    resolved instanceof ResolvedType.Declared d
                            ? d.symbol()
                            : resolved instanceof ResolvedType.Page p
                                    ? p.collection().symbol()
                                    : null;
            if (target == null) return;
            node(target);
            edges.add(
                    new Edge(
                            owner, target.identity().qualifiedName(), relation, role, cardinality));
        }

        void fields(
                VernacSourceFile source, String owner, List<FieldNode> fields, String relation) {
            for (var field : fields)
                reference(
                        source,
                        owner,
                        field.type(),
                        relation,
                        field.name(),
                        field.type().isOptional() ? "0..1" : "1");
        }

        String field(FieldNode f) {
            return (f.isMutable() ? "mut " : "")
                    + f.name()
                    + ": "
                    + f.type().name()
                    + (f.type().isOptional() ? "?" : "");
        }

        List<String> fieldMembers(List<FieldNode> fields) {
            return fields.stream().map(this::field).toList();
        }

        String owner(VernacSourceFile source, String name) {
            return source.unit().namespace() + "." + name;
        }

        void definition(VernacSourceFile source, TopLevelDefinition definition) {
            var mutable = MutableDomain.of(definition);
            if (mutable.isPresent()) {
                var m = mutable.get();
                String id = owner(source, m.name());
                var values = new ArrayList<String>();
                values.add("id: " + m.id().type().name());
                values.addAll(fieldMembers(m.fields()));
                m.methods().stream()
                        .filter(x -> !x.accessModifier().equals("private"))
                        .forEach(
                                x ->
                                        values.add(
                                                x.mode().name().toLowerCase(Locale.ROOT)
                                                        + " "
                                                        + x.name()
                                                        + "()"));
                members(id, values);
                reference(source, id, m.id().type(), "identity", "id", "1");
                fields(source, id, m.fields(), "contains");
            }
            if (definition instanceof ValueObjectNode vo) {
                String id = owner(source, vo.name());
                var values =
                        new ArrayList<>(
                                vo.isEnum()
                                        ? vo.enumConstants().stream()
                                                .map(EnumConstantNode::name)
                                                .toList()
                                        : fieldMembers(vo.fields()));
                vo.methods().stream()
                        .filter(m -> !m.accessModifier().equals("private"))
                        .forEach(m -> values.add(m.name() + "()"));
                members(id, values);
                fields(source, id, vo.fields(), "contains");
            }
            for (var collection : CollectionDeclaration.of(definition)) {
                String id = owner(source, collection.name());
                var values = new ArrayList<String>();
                values.add(
                        collection.definition().kind().name().toLowerCase(Locale.ROOT)
                                + " of "
                                + collection.elementName());
                collection.definition().customMethods().stream()
                        .filter(m -> !m.accessModifier().equals("private"))
                        .forEach(m -> values.add(m.name() + "()"));
                members(id, values);
                reference(
                        source,
                        id,
                        new TypeNode(definition.location(), collection.elementName()),
                        "elements",
                        "elements",
                        "0..*");
            }
            if (definition instanceof RepositoryNode repo) {
                String id = owner(source, repo.name());
                members(id, repo.methods().stream().map(q -> q.name() + "()").toList());
                reference(
                        source,
                        id,
                        new TypeNode(repo.location(), repo.aggregateName()),
                        "repository for",
                        "aggregate",
                        "*");
            }
            if (definition instanceof UseCaseNode usecase) {
                String id = owner(source, usecase.name());
                var values = new ArrayList<>(fieldMembers(usecase.parameters()));
                values.add("execute()");
                members(id, values);
                fields(source, id, usecase.injections(), "uses");
            }
            if (definition instanceof DomainServiceNode service) {
                String id = owner(source, service.name());
                members(
                        id,
                        service.methods().stream()
                                .filter(m -> !m.method().accessModifier().equals("private"))
                                .map(m -> m.method().name() + "()")
                                .toList());
                fields(source, id, service.injections(), "uses");
            }
            if (definition instanceof PortNode port)
                members(
                        owner(source, port.name()),
                        port.methods().stream().map(m -> m.method().name() + "()").toList());
            if (definition instanceof OutboundAdapterNode adapter) {
                String id = owner(source, adapter.name());
                reference(source, id, adapter.portType(), "implements", "port", "1");
                fields(source, id, adapter.dependencies(), "uses");
            }
            if (definition instanceof InboundHttpAdapterNode adapter) {
                String id = owner(source, adapter.name());
                members(
                        id,
                        adapter.endpoints().stream().map(e -> e.verb() + " " + e.route()).toList());
                fields(source, id, adapter.dependencies(), "uses");
            }
            if (definition instanceof EventNode event) {
                String id = owner(source, event.name());
                members(id, fieldMembers(event.fields()));
                fields(source, id, event.fields(), "payload");
            }
            if (definition instanceof ListenerNode listener) {
                String id = owner(source, listener.name());
                reference(
                        source,
                        id,
                        listener.eventType(),
                        "listens to",
                        listener.deferred() ? "deferred" : "synchronous",
                        "1");
                fields(source, id, listener.operation().injections(), "uses");
            }
        }
    }

    private static Map<String, String> aliases(Diagram diagram) {
        var names = new LinkedHashMap<String, String>();
        for (var node : diagram.nodes()) names.put(node.identity(), "n" + names.size());
        return names;
    }

    private static String label(String text) {
        return text.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("|", "&#124;")
                .replace("`", "&#96;")
                .replace("\\", "&#92;")
                .replace("\r", " ")
                .replace("\n", " ");
    }

    private static String title(Node node, String namespace) {
        String name =
                node.identity().startsWith(namespace + ".")
                                && node.identity().substring(namespace.length() + 1).indexOf('.')
                                        < 0
                        ? node.identity().substring(namespace.length() + 1)
                        : node.identity();
        return name
                + " · "
                + node.kind().toLowerCase(Locale.ROOT)
                + (node.dependency() ? " · module" : "");
    }

    public static String mermaid(Diagram diagram) {
        var names = aliases(diagram);
        var text = new StringBuilder("flowchart TD\n");
        for (var n : diagram.nodes())
            text.append("    ")
                    .append(names.get(n.identity()))
                    .append("[\"")
                    .append(label(title(n, diagram.namespace())))
                    .append("\"]\n");
        for (var e : diagram.edges())
            text.append("    ")
                    .append(names.get(e.from()))
                    .append(e.relation().equals("implements") ? " -.-> " : " --> ")
                    .append("|\"")
                    .append(label(e.relation() + ": " + e.role() + " [" + e.multiplicity() + "]"))
                    .append("\"| ")
                    .append(names.get(e.to()))
                    .append("\n");
        text.append(
                        "    classDef aggregate"
                                + " fill:#dbeafe,stroke:#2563eb,color:#172554,stroke-width:3px\n")
                .append("    classDef entity fill:#ede9fe,stroke:#7c3aed,color:#2e1065\n")
                .append("    classDef value fill:#dcfce7,stroke:#16a34a,color:#14532d\n")
                .append("    classDef operation fill:#ffedd5,stroke:#ea580c,color:#431407\n")
                .append(
                        "    classDef boundary"
                            + " fill:#f1f5f9,stroke:#64748b,color:#0f172a,stroke-dasharray:5 5\n");
        for (var n : diagram.nodes()) {
            String style =
                    n.dependency()
                                    || !n.identity()
                                            .substring(0, n.identity().lastIndexOf('.'))
                                            .equals(diagram.namespace())
                            ? "boundary"
                            : switch (n.kind()) {
                                case "AGGREGATE" -> "aggregate";
                                case "ENTITY" -> "entity";
                                case "VALUE_OBJECT", "ENUM", "ID", "COLLECTION" -> "value";
                                default -> "operation";
                            };
            text.append("    class ")
                    .append(names.get(n.identity()))
                    .append(" ")
                    .append(style)
                    .append("\n");
        }
        if (diagram.nodes().isEmpty())
            text.append("    empty[\"No executable definitions in this namespace\"]\n");
        return text.toString();
    }

    private static String plantText(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", " ")
                .replace("\n", " ")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    public static String plantuml(Diagram diagram) {
        var names = aliases(diagram);
        var text =
                new StringBuilder(
                        "@startuml\n"
                                + "!pragma layout smetana\n"
                                + "skinparam shadowing false\n"
                                + "skinparam classAttributeIconSize 0\n");
        for (var n : diagram.nodes())
            text.append("class \"")
                    .append(plantText(title(n, diagram.namespace())))
                    .append("\" as ")
                    .append(names.get(n.identity()))
                    .append(" <<")
                    .append(n.kind().toLowerCase(Locale.ROOT))
                    .append(">>\n");
        for (var e : diagram.edges())
            text.append(names.get(e.from()))
                    .append(e.relation().equals("implements") ? " ..> " : " --> ")
                    .append(names.get(e.to()))
                    .append(" : ")
                    .append(
                            plantText(
                                    e.relation() + " " + e.role() + " [" + e.multiplicity() + "]"))
                    .append("\n");
        return text.append("@enduml\n").toString();
    }

    private static String cell(String text) {
        return Vocabulary.escape(text).replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    public static String markdown(Diagram diagram) {
        var text =
                new StringBuilder("# ")
                        .append(cell(diagram.namespace()))
                        .append(" — domain model\n\n")
                        .append(
                                "Generated from resolved Vernac declarations. Open Markdown Preview"
                                        + " with the Mermaid extension enabled.\n\n")
                        .append(
                                "Blue: aggregates; purple: entities; green: values/IDs/collections;"
                                        + " orange: operations/integration; dashed: other"
                                        + " namespaces.\n\n"
                                        + "```mermaid\n")
                        .append(mermaid(diagram))
                        .append(
                                "```\n\n"
                                        + "## Model vocabulary and public shape\n\n"
                                        + "| Definition | Kind | Members | Meaning |\n"
                                        + "| --- | --- | --- | --- |\n");
        for (var node : diagram.nodes())
            text.append("| ")
                    .append(cell(node.identity()))
                    .append(" | ")
                    .append(cell(node.kind()))
                    .append(" | ")
                    .append(cell(String.join("; ", node.members())))
                    .append(" | ")
                    .append(cell(node.description()))
                    .append(" |\n");
        return text.append(
                        "\n"
                            + "Edges describe declared fields, collection elements, dependencies,"
                            + " repository targets and subscriptions. They do not prove exclusive"
                            + " ownership, runtime call order, event emission or purity. Boundary"
                            + " nodes are not expanded. Technical generated classes and arbitrary"
                            + " Java bodies are omitted.\n")
                .toString();
    }

    public static Path write(ResolvedProject project, String namespace, Path output)
            throws java.io.IOException {
        var diagram = model(project, namespace);
        Files.createDirectories(output);
        Files.writeString(output.resolve(namespace + ".mmd"), mermaid(diagram));
        Files.writeString(output.resolve(namespace + ".puml"), plantuml(diagram));
        Files.writeString(
                output.resolve(namespace + ".json"),
                org.vernac.compiler.modules.ModuleDescriptor.JSON
                                .writerWithDefaultPrettyPrinter()
                                .writeValueAsString(diagram)
                        + "\n");
        Path page = output.resolve(namespace + ".md");
        Files.writeString(page, markdown(diagram));
        return page;
    }

    public static void writeAll(ResolvedProject project, Path output) throws java.io.IOException {
        var text = new StringBuilder("# Vernac domain diagrams\n\n");
        for (String namespace : namespaces(project)) {
            write(project, namespace, output);
            text.append("- [").append(namespace).append("](").append(namespace).append(".md)\n");
        }
        Files.createDirectories(output);
        Files.writeString(output.resolve("index.md"), text.toString());
    }
}
