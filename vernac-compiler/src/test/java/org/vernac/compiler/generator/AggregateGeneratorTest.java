package org.vernac.compiler.generator;

import com.palantir.javapoet.JavaFile;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.parser.VernacLexer;
import org.vernac.compiler.parser.VernacParser;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AggregateGeneratorTest {

    private final AggregateGenerator generator = new AggregateGenerator();

    private CompilationUnitNode parse(String source) {
        VernacLexer lexer = new VernacLexer(CharStreams.fromString(source));
        VernacParser parser = new VernacParser(new CommonTokenStream(lexer));
        return new AstBuilderVisitor().visitCompilationUnit(parser.compilationUnit());
    }

    private Map<String, ValueObjectNode> extractValueObjects(CompilationUnitNode cu) {
        Map<String, ValueObjectNode> map = new HashMap<>();
        for (TopLevelDefinition def : cu.definitions()) {
            if (def instanceof ValueObjectNode vo) {
                map.put(vo.name(), vo);
            }
        }
        return map;
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
                        budget(newBudget);
                    }
                };
                """;

        CompilationUnitNode cu = parse(src);
        AggregateNode node = cu.aggregates().getFirst();
        JavaFile file = generator.generate(node, Map.of(), Map.of(), "com.example.domain", List.of());
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
                .contains("protected void markAsUpdated()")
                .contains("public void budget(Money budget)")
                .contains("markAsUpdated();")
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

    @Test
    @DisplayName("Übernimmt Java-Block mit Event-Emitting (registerEvent) fehlerfrei")
    void shouldGenerateAggregateWithEventEmitting() {
        String src = """
                package com.example.domain;
                
                aggregate Project[ProjectId](ProjectName name) {
                    public void rename(ProjectName newName) {
                        this.name = newName;
                        registerEvent(ProjectRenamed.create(this.id, newName));
                    }
                };
                """;

        CompilationUnitNode cu = parse(src);
        AggregateNode node = cu.aggregates().getFirst();
        JavaFile file = generator.generate(node, Map.of(), Map.of(), "com.example.domain", List.of());
        String code = file.toString().replaceAll("\\s+", " ");

        assertThat(code)
                .contains("public void rename(ProjectName newName)")
                .contains("this.name = newName;")
                .contains("registerEvent(ProjectRenamed.create(this.id, newName));");
    }

    @Test
    @DisplayName("Nutzt konsistent explizite und abgeleitete Feldnamen inkl. Mutatoren durch alle Methoden")
    void shouldGenerateAggregateWithExplicitAndDerivedNames() {
        String src = """
                package com.example.domain;
                aggregate Customer[CustomerId](String, mut String customAlias);
                """;

        CompilationUnitNode cu = parse(src);
        AggregateNode node = cu.aggregates().getFirst();
        JavaFile file = generator.generate(node, Map.of(), Map.of(), "com.example.domain", List.of());
        String code = file.toString().replaceAll("\\s+", " ");

        assertThat(code)
                .contains("private final String string;")
                .contains("private String customAlias;")
                .contains("private Customer(CustomerId id, String string, String customAlias, Instant createdAt, Instant updatedAt, long version, boolean validate)")
                .contains("public static Customer create(CustomerId id, String string, String customAlias)")
                .contains("public static Customer reconstitute(CustomerId id, String string, String customAlias, Instant createdAt, Instant updatedAt, long version)")
                .contains("public void customAlias(String customAlias) {")
                .contains("this.customAlias = customAlias;")
                .contains("public String string() { return this.string; }")
                .contains("public String customAlias() { return this.customAlias; }");
    }

    @Test
    @DisplayName("Erzeugt TABLE_NAME und SCHEMA_DDL mit korrektem Value-Object-Flattening")
    void shouldGenerateSchemaConstantsWithFlattenedColumns() {
        String src = """
                package com.example.domain;
                
                value ProjectName(String value);
                value Currency(String code);
                value Money(BigDecimal amount, Currency currency);
                
                aggregate Project[ProjectId](ProjectName name, Money budget);
                """;

        CompilationUnitNode cu = parse(src);
        Map<String, ValueObjectNode> valueObjects = extractValueObjects(cu);
        AggregateNode node = cu.aggregates().getFirst();

        JavaFile file = generator.generate(node, valueObjects, Map.of(), "com.example.domain", List.of());
        String code = file.toString();

        assertThat(code)
                .contains("public static final String TABLE_NAME = \"project\";")
                .contains("public static final String SCHEMA_DDL =")
                .contains("CREATE TABLE IF NOT EXISTS project (")
                .contains("id UUID PRIMARY KEY")
                .contains("name VARCHAR(255) NOT NULL")
                .contains("budget_amount NUMERIC(19, 4) NOT NULL")
                .contains("budget_currency VARCHAR(255) NOT NULL")
                .contains("created_at TIMESTAMPTZ NOT NULL")
                .contains("updated_at TIMESTAMPTZ NOT NULL")
                .contains("version BIGINT NOT NULL DEFAULT 0");
    }
}