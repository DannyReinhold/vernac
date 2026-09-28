package org.vernac.compiler.pipeline;

import com.squareup.javapoet.JavaFile;
import org.antlr.v4.runtime.*;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.generator.AggregateGenerator;
import org.vernac.compiler.generator.EntityGenerator;
import org.vernac.compiler.generator.EventGenerator;
import org.vernac.compiler.generator.ValueObjectGenerator;
import org.vernac.compiler.parser.VernacLexer;
import org.vernac.compiler.parser.VernacParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class VernacCompiler {

    private final ValueObjectGenerator valueObjectGenerator = new ValueObjectGenerator();
    private final EventGenerator eventGenerator = new EventGenerator();
    private final AggregateGenerator aggregateGenerator = new AggregateGenerator();
    private final EntityGenerator entityGenerator = new EntityGenerator();

    public VernacCompilationResult compile(Path vernacFile) throws IOException {
        String source = Files.readString(vernacFile);
        return compileSource(source);
    }

    public VernacCompilationResult compileSource(String source) {
        CompilationUnitNode unit = parse(source);
        String packageName = unit.packageName().orElse("generated.domain");

        List<JavaFile> generatedFiles = new ArrayList<>();

        List<String> imports = unit.imports();
        for (TopLevelDefinition definition : unit.definitions()) {
            if (definition instanceof ValueObjectNode vo) {
                generatedFiles.add(valueObjectGenerator.generate(vo, packageName, imports));
            } else if (definition instanceof EventNode event) {
                generatedFiles.add(eventGenerator.generate(event, packageName, imports));
            } else if (definition instanceof AggregateNode agg) {
                generatedFiles.add(aggregateGenerator.generate(agg, packageName, imports));
            } else if (definition instanceof EntityNode entity) {
                generatedFiles.add(entityGenerator.generate(entity, packageName, imports));
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
        public void syntaxError(
                Recognizer<?, ?> recognizer,
                Object offendingSymbol,
                int line,
                int charPositionInLine,
                String msg,
                RecognitionException e
        ) {
            throw new IllegalArgumentException("Syntax error at line " + line + ":" + (charPositionInLine + 1) + " - " + msg, e);
        }
    }
}