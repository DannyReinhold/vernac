package org.vernac.compiler.generator;

import com.squareup.javapoet.JavaFile;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vernac.compiler.ast.AstBuilderVisitor;
import org.vernac.compiler.ast.CompilationUnitNode;
import org.vernac.compiler.ast.EntityNode;
import org.vernac.compiler.parser.VernacLexer;
import org.vernac.compiler.parser.VernacParser;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EntityGeneratorTest {

    private final EntityGenerator generator = new EntityGenerator();

    private CompilationUnitNode parse(String source) {
        VernacLexer lexer = new VernacLexer(CharStreams.fromString(source));
        VernacParser parser = new VernacParser(new CommonTokenStream(lexer));
        return new AstBuilderVisitor().visitCompilationUnit(parser.compilationUnit());
    }

    @Test
    @DisplayName("Generiert Entity mit Entity-Interface, mut-Settern, create/reconstitute und ID-Semantik")
    void shouldGenerateEntity() {
        String src = """
                package com.example.domain;
                
                entity Task[TaskId](TaskTitle title, mut TaskStatus status) validates {
                    require(title.value().length() <= 100, "Title too long");
                } {
                    public void complete() {
                        setStatus(TaskStatus.COMPLETED);
                    }
                };
                """;

        CompilationUnitNode cu = parse(src);
        EntityNode node = cu.entities().getFirst();
        JavaFile file = generator.generate(node, "com.example.domain", List.of());
        String code = file.toString();
        String normalizedCode = code.replaceAll("\\s+", " ");

        assertThat(code)
                .contains("public class Task implements Entity<TaskId>")
                .contains("private final TaskId id;")
                .contains("private final TaskTitle title;")
                .contains("private TaskStatus status;")
                .contains("private void setStatus(TaskStatus status)")
                .contains("validate();")
                .contains("public void complete()")
                .contains("public TaskId id()")
                .contains("public TaskTitle title()")
                .contains("public TaskStatus status()")
                .contains("return Objects.equals(this.id, that.id);")
                .contains("return Objects.hash(this.id);")
                .contains("return \"Task[id=\" + this.id + \"]\";");

        assertThat(normalizedCode)
                .contains("public static Task create(TaskId id, TaskTitle title, TaskStatus status)")
                .contains("public static Task reconstitute(TaskId id, TaskTitle title, TaskStatus status)");
    }
}