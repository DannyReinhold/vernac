// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.pipeline;

import com.palantir.javapoet.JavaFile;
import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.analyzer.SemanticAnalyzer;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.generator.*;

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
    private final RepositoryGenerator repositoryGenerator = new RepositoryGenerator();
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

    /** Compiles the reviewed project slice: IDs, value objects, enums and their collections. */
    public VernacProjectCompilationResult compileProject(Path sourceRoot) throws IOException {
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
                    new BehaviorGenerator().generate(com.palantir.javapoet.ClassName.get(source.unit().namespace() + ".domain", value.name()),
                            value.methods(), value.javaImports(), source.unit(), project).ifPresent(files::add);
                }
                CollectionDeclaration.of(definition).ifPresent(collection -> {
                    files.add(domainCollectionGenerator.generate(collection, source.unit().namespace(), project));
                    new BehaviorGenerator().generate(com.palantir.javapoet.ClassName.get(source.unit().namespace() + ".domain", collection.name()),
                            collection.definition().customMethods(), collection.definition().javaImports(), source.unit(), project).ifPresent(files::add);
                });
            }
        }
        return new VernacProjectCompilationResult(files, project.diagnostics());
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
                new BehaviorGenerator().generate(com.palantir.javapoet.ClassName.get(packageName + ".domain", vo.name()),
                        vo.methods(), vo.javaImports(), unit, resolved).ifPresent(generatedFiles::add);

            } else if (definition instanceof EventNode event) {
                generatedFiles.add(eventGenerator.generate(event, packageName, imports));
            } else if (definition instanceof AggregateNode agg) {
                generatedFiles.add(aggregateGenerator.generate(agg, valueObjects, entities, packageName, imports));
            } else if (definition instanceof EntityNode entity) {
                generatedFiles.add(entityGenerator.generate(entity, valueObjects, entities, packageName, imports));

            } else if (definition instanceof RepositoryNode repo) {
                AggregateNode targetAgg = aggregates.get(repo.aggregateName());
                generatedFiles.addAll(repositoryGenerator.generate(
                        repo, targetAgg, entities, valueObjects, packageName, imports
                ));
            } else if (definition instanceof PortNode port) {
                generatedFiles.addAll(portGenerator.generate(port, packageName, imports));
            } else if (definition instanceof UseCaseNode useCase) {
                generatedFiles.add(useCaseGenerator.generate(useCase, aggregates, repositories, packageName, imports));
            } else if (definition instanceof DomainServiceNode service) { // <-- DIESER ZWEIG FEHLT
                generatedFiles.add(domainServiceGenerator.generate(service, aggregates, packageName, imports));
            } else if (definition instanceof ListenerNode listener) { // <-- NEU
                generatedFiles.add(listenerGenerator.generate(listener, packageName, imports));
            }
        }

        return new VernacCompilationResult(packageName, generatedFiles);
    }

}
