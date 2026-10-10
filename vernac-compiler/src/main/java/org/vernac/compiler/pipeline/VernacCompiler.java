// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.pipeline;

import com.palantir.javapoet.JavaFile;
import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.analyzer.SemanticAnalyzer;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.generator.*;
import org.vernac.compiler.persistence.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VernacCompiler {

    private final SemanticAnalyzer semanticAnalyzer = new SemanticAnalyzer();
    private final IdGenerator idGenerator = new IdGenerator();
    private final ValueObjectGenerator valueObjectGenerator = new ValueObjectGenerator();
    private final EventGenerator eventGenerator = new EventGenerator();
    private final AggregateGenerator aggregateGenerator = new AggregateGenerator();
    private final EntityGenerator entityGenerator = new EntityGenerator();
    private final DomainCollectionGenerator domainCollectionGenerator = new DomainCollectionGenerator(); // <-- NEU
    private final PortGenerator portGenerator = new PortGenerator();
    private final UseCaseGenerator useCaseGenerator = new UseCaseGenerator();
    private final DomainServiceGenerator domainServiceGenerator = new DomainServiceGenerator();
    private final ListenerGenerator listenerGenerator = new ListenerGenerator();

    public VernacCompilationResult compile(Path vernacFile) throws IOException {
        String source = Files.readString(vernacFile);
        return generate(new VernacSourceParser().parse(vernacFile.toString(), source));
    }

    public VernacCompilationResult compileSource(String source) {
        return generate(new VernacSourceParser().parse("<memory>", source));
    }

    /** Reads and indexes the complete source tree before reference resolution. */
    public VernacProject readProject(Path sourceRoot) throws IOException {
        return new VernacProjectLoader().load(sourceRoot);
    }

    /** Validates file imports and resolves value-object field types against the project index. */
    public ResolvedProject analyzeProject(Path sourceRoot) throws IOException {
        return new ProjectTypeResolver().resolve(readProject(sourceRoot));
    }

    /** Compiles reviewed domain types, collections and explicit JDBC repositories. */
    public VernacProjectCompilationResult compileProject(Path sourceRoot) throws IOException {
        return compileProject(sourceRoot, SchemaModel.empty(), Map.of());
    }
    public VernacProjectCompilationResult compileProject(Path sourceRoot, SchemaModel previous,
            Map<String,Map<String,String>> enumCodes) throws IOException {
        ResolvedProject project = analyzeProject(sourceRoot);
        if (!project.deferredTypes().isEmpty()) {
            throw new SemanticValidationException(project.deferredTypes().stream().map(symbol ->
                    CompilerDiagnostic.error(symbol.location(), "Project generation for " + symbol.kind()
                            + " '" + symbol.identity().qualifiedName() + "' has not been migrated yet.")).toList());
        }
        List<JavaFile> files = new ArrayList<>();
        for (var source : project.project().sources()) {
            for (var definition : source.unit().definitions()) {
                if (definition instanceof IdDeclarationNode id) files.add(idGenerator.generate(id, source.unit().namespace()));
                else if (definition instanceof ValueObjectNode value) {
                    files.add(valueObjectGenerator.generate(value, source.unit().namespace(), project));
                    files.addAll(valueValidation(value, source.unit(), project));
                    new BehaviorGenerator().generate(com.palantir.javapoet.ClassName.get(source.unit().namespace() + ".domain", value.name()),
                            value.methods(), value.javaImports(), source.unit(), project).ifPresent(files::add);
                }
                if (definition instanceof UseCaseNode u) files.addAll(useCaseGenerator.generate(u,source.unit(),project));
                MutableDomain.of(definition).ifPresent(model -> files.addAll(generateMutable(model, source.unit(), project)));
                CollectionDeclaration.of(definition).ifPresent(collection -> {
                    files.add(domainCollectionGenerator.generate(collection, source.unit().namespace(), project));
                    new BehaviorGenerator().generate(com.palantir.javapoet.ClassName.get(source.unit().namespace() + ".domain", collection.name()),
                            collection.definition().customMethods(), collection.definition().javaImports(), source.unit(), project).ifPresent(files::add);
                });
            }
        }
        SchemaBuilder persistence = new SchemaBuilder(project, previous, enumCodes);
        SchemaModel schema = persistence.build();
        for (StoragePlan plan : persistence.plans()) files.addAll(new JdbcRepositoryGenerator(project,schema).generate(plan));
        GeneratedTypeNames.check(files);
        return new VernacProjectCompilationResult(files, project.diagnostics());
    }

    private List<JavaFile> valueValidation(ValueObjectNode value, CompilationUnitNode unit, ResolvedProject project) {
        if (value.validations().isEmpty()) return List.of();
        var owner = com.palantir.javapoet.ClassName.get(unit.namespace() + ".domain", value.name());
        var files = new ArrayList<JavaFile>();
        files.add(new ValueReadGenerator().generate(value, owner, project));
        new ValidationGenerator().generate(owner, value.validations(), value.javaImports(), unit, project).ifPresent(files::add);
        return files;
    }

    private List<JavaFile> generateMutable(MutableDomain model, CompilationUnitNode unit, ResolvedProject project) {
        var invalid = model.methods().stream().filter(m -> m.accessModifier().equals("public") && m.mode() == MethodNode.Mode.DEFAULT).toList();
        if (!invalid.isEmpty()) throw new SemanticValidationException(invalid.stream().map(m -> CompilerDiagnostic.error(m.location(),
                "Entity/aggregate behavior requires read or modify instead of public. Move legacy inline methods into behavior.")).toList());
        if (model.definition() instanceof EntityNode e && e.customPackage().isPresent()
                || model.definition() instanceof AggregateNode a && a.customPackage().isPresent())
            throw new SemanticValidationException(List.of(CompilerDiagnostic.error(model.definition().location(), "Entity and aggregate packages derive from their namespace.")));
        Map<String, ValueObjectNode> values = new HashMap<>();
        Map<String, EntityNode> entities = new HashMap<>();
        for (var source : project.project().sources()) for (var definition : source.unit().definitions()) {
            if (definition instanceof ValueObjectNode v) values.put(v.name(), v);
            if (definition instanceof EntityNode e) entities.put(e.name(), e);
        }
        List<JavaFile> files = new ArrayList<>();
        if (model.definition() instanceof EntityNode e) files.add(new EntityGenerator(project).generate(e, values, entities, unit.namespace(), List.of()));
        else files.add(new AggregateGenerator(project).generate((AggregateNode) model.definition(), values, entities, unit.namespace(), List.of()));
        var owner = com.palantir.javapoet.ClassName.get(unit.namespace() + ".domain", model.name());
        files.addAll(new DomainAccessGenerator().interfaces(model, owner, project));
        var rules = model.definition() instanceof EntityNode e ? e.validations() : ((AggregateNode) model.definition()).validations();
        new ValidationGenerator().generate(owner, rules, model.imports(), unit, project).ifPresent(files::add);
        new BehaviorGenerator().generate(owner, model.methods(), model.imports(), unit, project).ifPresent(files::add);
        return files;
    }

    private VernacCompilationResult generate(CompilationUnitNode unit) {

        // Semantische Analyse vor der Codegenerierung
        List<CompilerDiagnostic> diagnostics = semanticAnalyzer.analyze(unit);
        List<CompilerDiagnostic> errors = diagnostics.stream()
                .filter(d -> d.severity() == CompilerDiagnostic.Severity.ERROR)
                .toList();

        if (!errors.isEmpty()) {
            throw new SemanticValidationException(errors);
        }

        Path memoryRoot = Path.of(".").toAbsolutePath().normalize();
        var indexed = new VernacProjectLoader().index(memoryRoot,
                List.of(new VernacSourceFile(memoryRoot.resolve("memory.vernac"), unit)));
        ResolvedProject resolved = new ProjectTypeResolver().resolve(indexed);

        String packageName = unit.namespace();
        List<String> imports = unit.imports().stream().map(ImportNode::text).toList();
        List<JavaFile> generatedFiles = new ArrayList<>();

        // Lookup-Maps für Typ-Beziehungen aufbauen
        Map<String, AggregateNode> aggregates = new HashMap<>();
        Map<String, EntityNode> entities = new HashMap<>();
        Map<String, ValueObjectNode> valueObjects = new HashMap<>();
        Map<String, RepositoryNode> repositories = new HashMap<>();

        for (TopLevelDefinition def : unit.definitions()) {
            if (def instanceof AggregateNode agg) aggregates.put(agg.name(), agg);
            else if (def instanceof EntityNode entity) entities.put(entity.name(), entity);
            else if (def instanceof ValueObjectNode vo) valueObjects.put(vo.name(), vo);
            else if (def instanceof RepositoryNode repo) repositories.put(repo.name(), repo);
        }

        // Bestehende Generierungsschleife
        for (TopLevelDefinition definition : unit.definitions()) {
            CollectionDeclaration.of(definition).ifPresent(collection -> {
                generatedFiles.add(domainCollectionGenerator.generate(collection, packageName, resolved));
                new BehaviorGenerator().generate(com.palantir.javapoet.ClassName.get(packageName + ".domain", collection.name()),
                        collection.definition().customMethods(), collection.definition().javaImports(), unit, resolved).ifPresent(generatedFiles::add);
            });
            if (definition instanceof IdDeclarationNode idDef) {
                generatedFiles.add(idGenerator.generate(idDef, packageName));
            } else if (definition instanceof ValueObjectNode vo) {
                generatedFiles.add(valueObjectGenerator.generate(vo, packageName, resolved));
                generatedFiles.addAll(valueValidation(vo, unit, resolved));
                new BehaviorGenerator().generate(com.palantir.javapoet.ClassName.get(packageName + ".domain", vo.name()),
                        vo.methods(), vo.javaImports(), unit, resolved).ifPresent(generatedFiles::add);

            } else if (definition instanceof EventNode event) {
                generatedFiles.add(eventGenerator.generate(event, packageName, imports));
            } else if (definition instanceof AggregateNode agg) {
                generatedFiles.addAll(generateMutable(MutableDomain.of(agg).orElseThrow(), unit, resolved));
            } else if (definition instanceof EntityNode entity) {
                generatedFiles.addAll(generateMutable(MutableDomain.of(entity).orElseThrow(), unit, resolved));

            } else if (definition instanceof RepositoryNode repo) {
                SchemaBuilder persistence = new SchemaBuilder(resolved, SchemaModel.empty());
                SchemaModel schema = persistence.build();
                for (StoragePlan plan : persistence.plans()) if (plan.repository().name().equals(repo.name()))
                    generatedFiles.addAll(new JdbcRepositoryGenerator(resolved,schema).generate(plan));
            } else if (definition instanceof PortNode port) {
                generatedFiles.addAll(portGenerator.generate(port, packageName, imports));
            } else if (definition instanceof UseCaseNode useCase) {
                generatedFiles.addAll(useCaseGenerator.generate(useCase, unit, resolved));
            } else if (definition instanceof DomainServiceNode service) { // <-- DIESER ZWEIG FEHLT
                generatedFiles.add(domainServiceGenerator.generate(service, aggregates, packageName, imports));
            } else if (definition instanceof ListenerNode listener) { // <-- NEU
                generatedFiles.add(listenerGenerator.generate(listener, packageName, imports));
            }
        }

        GeneratedTypeNames.check(generatedFiles);
        return new VernacCompilationResult(packageName, generatedFiles);
    }

}
