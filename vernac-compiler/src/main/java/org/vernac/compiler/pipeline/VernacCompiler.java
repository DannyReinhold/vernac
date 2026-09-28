package org.vernac.compiler.pipeline;

import com.squareup.javapoet.JavaFile;
import org.antlr.v4.runtime.*;
import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.analyzer.SemanticAnalyzer;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.generator.*;
import org.vernac.compiler.parser.VernacLexer;
import org.vernac.compiler.parser.VernacParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VernacCompiler {

    private final SemanticAnalyzer semanticAnalyzer = new SemanticAnalyzer();
    private final ValueObjectGenerator valueObjectGenerator = new ValueObjectGenerator();
    private final EventGenerator eventGenerator = new EventGenerator();
    private final AggregateGenerator aggregateGenerator = new AggregateGenerator();
    private final EntityGenerator entityGenerator = new EntityGenerator();
    private final RepositoryGenerator repositoryGenerator = new RepositoryGenerator();

    public VernacCompilationResult compile(Path vernacFile) throws IOException {
        String source = Files.readString(vernacFile);
        return compileSource(source);
    }

    public VernacCompilationResult compileSource(String source) {
        CompilationUnitNode unit = parse(source);

        // Semantische Analyse vor der Codegenerierung
        List<CompilerDiagnostic> diagnostics = semanticAnalyzer.analyze(unit);
        List<CompilerDiagnostic> errors = diagnostics.stream()
                .filter(d -> d.severity() == CompilerDiagnostic.Severity.ERROR)
                .toList();

        if (!errors.isEmpty()) {
            throw new SemanticValidationException(errors);
        }

        String packageName = unit.packageName().orElse("generated.domain");
        List<String> imports = unit.imports();
        List<JavaFile> generatedFiles = new ArrayList<>();

        // Lookup-Maps für Typ-Beziehungen aufbauen
        Map<String, AggregateNode> aggregates = new HashMap<>();
        Map<String, EntityNode> entities = new HashMap<>();
        Map<String, ValueObjectNode> valueObjects = new HashMap<>();

        for (TopLevelDefinition def : unit.definitions()) {
            if (def instanceof AggregateNode agg) aggregates.put(agg.name(), agg);
            else if (def instanceof EntityNode entity) entities.put(entity.name(), entity);
            else if (def instanceof ValueObjectNode vo) valueObjects.put(vo.name(), vo);
        }

        // Bestehende Generierungsschleife
        for (TopLevelDefinition definition : unit.definitions()) {
            if (definition instanceof ValueObjectNode vo) {
                generatedFiles.add(valueObjectGenerator.generate(vo, packageName, imports));
            } else if (definition instanceof EventNode event) {
                generatedFiles.add(eventGenerator.generate(event, packageName, imports));
            } else if (definition instanceof AggregateNode agg) {
                generatedFiles.add(aggregateGenerator.generate(agg, packageName, imports));
            } else if (definition instanceof EntityNode entity) {
                generatedFiles.add(entityGenerator.generate(entity, packageName, imports));
            } else if (definition instanceof RepositoryNode repo) {
                AggregateNode targetAgg = aggregates.get(repo.aggregateName());
                generatedFiles.addAll(repositoryGenerator.generate(
                        repo, targetAgg, entities, valueObjects, packageName, imports
                ));
            }
        }

        return new VernacCompilationResult(packageName, generatedFiles);
    }

    private CompilationUnitNode parse(String source) {
        VernacLexer lexer = new VernacLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        lexer.addErrorListener(new DescriptiveErrorListener());

        VernacParser parser = new VernacParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(new DescriptiveErrorListener());

        return new AstBuilderVisitor().visitCompilationUnit(parser.compilationUnit());
    }

    private static class DescriptiveErrorListener extends BaseErrorListener {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine, String msg, RecognitionException e) {
            throw new IllegalArgumentException("Syntax error at line " + line + ":" + (charPositionInLine + 1) + " - " + msg, e);
        }
    }
}