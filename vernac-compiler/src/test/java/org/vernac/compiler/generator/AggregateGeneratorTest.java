package org.vernac.compiler.generator;

import com.squareup.javapoet.JavaFile;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vernac.compiler.ast.AggregateNode;
import org.vernac.compiler.ast.AstBuilderVisitor;
import org.vernac.compiler.ast.CompilationUnitNode;
import org.vernac.compiler.parser.VernacLexer;
import org.vernac.compiler.parser.VernacParser;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AggregateGeneratorTest {

    private final AggregateGenerator generator = new AggregateGenerator();

    private CompilationUnitNode parse(String source) {
        VernacLexer lexer = new VernacLexer(CharStreams.fromString(source));
        VernacParser parser = new VernacParser(new CommonTokenStream(lexer));
        return new AstBuilderVisitor().visitCompilationUnit(parser.compilationUnit());
    }

    @Test
    @DisplayName("Generiert vollständiges Aggregate Root mit Lifecycle, mut-Settern, Events und ID-Semantik")
    void shouldGenerateAggregateRoot() {
        String src = """
                package com.example.domain;
                
                aggregate Project[ProjectId](ProjectName name, mut Money budget) validates {
                    require(budget.amount().compareTo(BigDecimal.ZERO) >= 0, "Budget cannot be negative");
                } {
                    public void assignBudget(Money newBudget) {
                        setBudget(newBudget);
                    }
                };
                """;

        CompilationUnitNode cu = parse(src);
        AggregateNode node = cu.aggregates().getFirst();
        JavaFile file = generator.generate(node, "com.example.domain", List.of());
        String code = file.toString();
        String normalizedCode = code.replaceAll("\\s+", " ");

        assertThat(code)
                .contains("public class Project implements AggregateRoot<ProjectId>")
                .contains("private final transient List<DomainEvent> domainEvents = new ArrayList<>();")
                .contains("private final Instant createdAt;")
                .contains("private Instant updatedAt;")
                .contains("private long version;")
                .contains("private final ProjectId id;")
                .contains("private final ProjectName name;")
                .contains("private Money budget;")
                .contains("private void setBudget(Money budget)")
                .contains("validate();")
                .contains("public void assignBudget(Money newBudget)")
                .contains("public ProjectId id()")
                .contains("public Instant createdAt()")
                .contains("public Instant updatedAt()")
                .contains("public long version()")
                .contains("public List<DomainEvent> pullDomainEvents()")
                .contains("List<DomainEvent> events = List.copyOf(this.domainEvents);")
                .contains("this.domainEvents.clear();")
                .contains("return Objects.equals(this.id, that.id);")
                .contains("return Objects.hash(this.id);")
                .contains("return \"Project[id=\" + this.id + \"]\";");

        assertThat(normalizedCode)
                .contains("public static Project create(ProjectId id, ProjectName name, Money budget)")
                .contains("public static Project reconstitute(ProjectId id, ProjectName name, Money budget, Instant createdAt, Instant updatedAt, long version)");
    }
}