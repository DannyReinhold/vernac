// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.tooling;

import com.palantir.javapoet.JavaFile;
import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.generator.*;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.symbols.*;

import java.nio.file.Path;
import java.util.*;

/** Current domain Java APIs and access views from production generators, without build output. */
public final class EditorValueObjects {
    public record Value(String javaName, Path source, String javaSource) {}

    /** valueNames includes supported public declarations, even when their current API is invalid. */
    public record Model(Map<String, Value> values, Set<String> valueNames, Set<String> liveNames) {
        public Model {
            values = Map.copyOf(values);
            valueNames = Set.copyOf(valueNames);
            liveNames = Set.copyOf(liveNames);
        }
    }

    public Model build(VernacProject project) {
        var resolved = new ProjectTypeResolver().resolveForAnalysis(project);
        Map<String, Value> values = new LinkedHashMap<>();
        Set<String> names = new HashSet<>(), live = new HashSet<>();
        for (var source : project.sources()) {
            if (source.external()) continue;
            String namespace = source.unit().namespace();
            if (!org.vernac.language.VernacNames.isNamespace(namespace)
                    || !source.path().getParent().equals(
                            project.sourceRoot().resolve(namespace.replace('.', '/')))) continue;
            project.symbols().inNamespace(namespace).stream()
                    .filter(s -> s.sourceFile().equals(source.path()))
                    .forEach(s -> live.add(JavaTypeNames.canonicalName(new ResolvedType.Declared(s))));
            var definitions = source.unit().definitions();
            for (var definition : definitions) {
                var mutable = MutableDomain.of(definition);
                if (!(definition instanceof IdDeclarationNode)
                        && !(definition instanceof ValueObjectNode) && mutable.isEmpty()) continue;
                String name = definition instanceof IdDeclarationNode id ? id.name()
                        : definition instanceof ValueObjectNode value ? value.name() : mutable.orElseThrow().name();
                String javaName = namespace + ".domain." + name;
                var collections = CollectionDeclaration.of(definition);
                names.add(javaName);
                collections.forEach(c -> names.add(namespace + ".domain." + c.name()));
                if (resolved.scopes().get(source.path()).hasErrors()
                        || project.symbols().find(namespace + "." + name).isEmpty()) continue;
                SourceLocation end = definitions.stream().map(TopLevelDefinition::location)
                        .filter(l -> after(l, definition.location())).min(LOCATION_ORDER)
                        .orElse(new SourceLocation(Integer.MAX_VALUE, 0));
                SourceLocation primaryEnd = collections.stream().map(c -> c.definition().location())
                        .min(LOCATION_ORDER).orElse(end);
                if (invalid(resolved, source.path(), definition.location(), primaryEnd)) continue;
                if (definition instanceof IdDeclarationNode id) {
                    add(project, source, new IdGenerator().generate(id, namespace), values);
                } else if (mutable.isPresent()) {
                    var model = mutable.get();
                    if (!resolved.types().containsKey(model.id().type())
                            || model.fields().stream().anyMatch(f -> !resolved.types().containsKey(f.type())))
                        continue;
                    JavaFile generated = definition instanceof EntityNode entity
                            ? new EntityGenerator(resolved).generate(entity, namespace)
                            : new AggregateGenerator(resolved).generate((AggregateNode) definition, namespace);
                    add(project, source, generated, values);
                    for (var access : new DomainAccessGenerator().interfaces(model,
                            com.palantir.javapoet.ClassName.get(namespace + ".domain", name), resolved))
                        add(project, source, access, values);
                } else {
                    var value = (ValueObjectNode) definition;
                    if (value.fields().stream().anyMatch(f -> !resolved.types().containsKey(f.type())))
                        continue;
                    add(project, source, new ValueObjectGenerator().generate(value, namespace, resolved), values);
                    if (!value.validations().isEmpty()) {
                        add(project, source, new ValueReadGenerator().generate(value,
                                com.palantir.javapoet.ClassName.get(namespace + ".domain", name), resolved), values);
                    }
                }
                for (int i = 0; i < collections.size(); i++) {
                    var collection = collections.get(i);
                    SourceLocation collectionEnd = i + 1 < collections.size()
                            ? collections.get(i + 1).definition().location() : end;
                    if (project.symbols().find(namespace + "." + collection.name()).isEmpty()
                            || invalid(resolved, source.path(), collection.definition().location(), collectionEnd))
                        continue;
                    add(project, source, new DomainCollectionGenerator().generate(collection, namespace, resolved), values);
                }
            }
        }
        return new Model(values, names, live);
    }

    private static final Comparator<SourceLocation> LOCATION_ORDER =
            Comparator.comparingInt(SourceLocation::line).thenComparingInt(SourceLocation::column);

    private static boolean invalid(ResolvedProject project, Path source, SourceLocation start, SourceLocation end) {
        return project.diagnostics().stream()
                .filter(d -> d.severity() == CompilerDiagnostic.Severity.ERROR)
                .anyMatch(d -> d.location().sourceName().equals(source.toString())
                        && !after(start, d.location()) && after(end, d.location()));
    }

    private static void add(VernacProject project, VernacSourceFile source, JavaFile generated,
            Map<String, Value> values) {
        String name = generated.packageName() + "." + generated.typeSpec().name();
        String java = org.vernac.compiler.vocabulary.Vocabulary.documentJava(project, List.of(generated))
                .getFirst().toString();
        values.put(name, new Value(name, source.path(), java));
    }

    private static boolean after(SourceLocation first, SourceLocation second) {
        return LOCATION_ORDER.compare(first, second) > 0;
    }
}
