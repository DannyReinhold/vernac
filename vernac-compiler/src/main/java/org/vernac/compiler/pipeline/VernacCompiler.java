// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.pipeline;

import com.palantir.javapoet.JavaFile;
import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.generator.*;
import org.vernac.compiler.persistence.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class VernacCompiler {

    private final IdGenerator idGenerator = new IdGenerator();
    private final ValueObjectGenerator valueObjectGenerator = new ValueObjectGenerator();
    private final EventGenerator eventGenerator = new EventGenerator();
    private final DomainCollectionGenerator domainCollectionGenerator = new DomainCollectionGenerator();
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

    /** Validates imports and resolves all supported declarations against the project index. */
    public ResolvedProject analyzeProject(Path sourceRoot) throws IOException {
        return new ProjectTypeResolver().resolve(readProject(sourceRoot));
    }

    /** Compiles reviewed domain types, collections and explicit JDBC repositories. */
    public VernacProjectCompilationResult compileProject(Path sourceRoot) throws IOException {
        return compileProject(sourceRoot, SchemaModel.empty(), Map.of());
    }
    public VernacProjectCompilationResult compileProject(Path sourceRoot, SchemaModel previous,
            Map<String,Map<String,String>> enumCodes) throws IOException {
        return generate(analyzeProject(sourceRoot), previous, enumCodes);
    }

    private VernacProjectCompilationResult generate(ResolvedProject project, SchemaModel previous,
            Map<String, Map<String, String>> enumCodes) {
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
                if (definition instanceof EventNode e) files.addAll(eventGenerator.generate(e,source.unit(),project));
                if (definition instanceof ListenerNode l) files.addAll(listenerGenerator.generate(l,source.unit(),project));
                if (definition instanceof UseCaseNode u) files.addAll(useCaseGenerator.generate(u,source.unit(),project));
                if (definition instanceof PortNode p) files.addAll(portGenerator.generate(p,source.unit(),project));
                if (definition instanceof InboundHttpAdapterNode a) files.addAll(new InboundHttpGenerator().generate(a,source.unit(),project,project.inboundAdapters().get(a)));
                if (definition instanceof OutboundAdapterNode a) files.addAll(new OutboundAdapterGenerator().generate(a,source.unit(),project));
                if (definition instanceof DomainServiceNode s) files.addAll(domainServiceGenerator.generate(s,source.unit(),project));
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
        for (var plan : persistence.eventPlans()) files.add(new JdbcRepositoryGenerator(project,schema).generate(plan));
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
        List<JavaFile> files = new ArrayList<>();
        if (model.definition() instanceof EntityNode e) files.add(new EntityGenerator(project).generate(e, unit.namespace()));
        else files.add(new AggregateGenerator(project).generate((AggregateNode) model.definition(), unit.namespace()));
        var owner = com.palantir.javapoet.ClassName.get(unit.namespace() + ".domain", model.name());
        files.addAll(new DomainAccessGenerator().interfaces(model, owner, project));
        var rules = model.definition() instanceof EntityNode e ? e.validations() : ((AggregateNode) model.definition()).validations();
        new ValidationGenerator().generate(owner, rules, model.imports(), unit, project).ifPresent(files::add);
        new BehaviorGenerator().generate(owner, model.methods(), model.imports(), unit, project).ifPresent(files::add);
        return files;
    }

    private VernacCompilationResult generate(CompilationUnitNode unit) {
        Path memoryRoot = Path.of(".").toAbsolutePath().normalize();
        var indexed = new VernacProjectLoader().index(memoryRoot,
                List.of(new VernacSourceFile(memoryRoot.resolve("memory.vernac"), unit)));
        var resolved = new ProjectTypeResolver().resolve(indexed);
        var result = generate(resolved, SchemaModel.empty(), Map.of());
        return new VernacCompilationResult(unit.namespace(), result.generatedFiles());
    }
}
