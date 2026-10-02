package org.vernac.compiler.generator;

import com.squareup.javapoet.JavaFile;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vernac.compiler.ast.AstBuilderVisitor;
import org.vernac.compiler.ast.CompilationUnitNode;
import org.vernac.compiler.ast.EntityNode;
import org.vernac.compiler.ast.ValueObjectNode;
import org.vernac.compiler.parser.VernacLexer;
import org.vernac.compiler.parser.VernacParser;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DomainCollectionGeneratorTest {

    private final DomainCollectionGenerator generator = new DomainCollectionGenerator();

    private CompilationUnitNode parse(String source) {
        VernacLexer lexer = new VernacLexer(CharStreams.fromString(source));
        VernacParser parser = new VernacParser(new CommonTokenStream(lexer));
        return new AstBuilderVisitor().visitCompilationUnit(parser.compilationUnit());
    }

    @Test
    @DisplayName("Generiert Standard-Collection mit pluralisiertem Namen, Immutability und plus/minus")
    void shouldGenerateDefaultPluralizedCollection() {
        String src = """
                package com.example;
                value Car(String model) collection;
                """;

        CompilationUnitNode cu = parse(src);
        ValueObjectNode node = cu.definitions().stream()
                .filter(d -> d instanceof ValueObjectNode)
                .map(d -> (ValueObjectNode) d)
                .findFirst().orElseThrow();

        JavaFile file = generator.generate(node, "com.example", List.of());
        String code = file.toString();

        assertThat(code)
                .contains("package com.example.domain;")
                .contains("public final class Cars implements Iterable<Car>")
                .contains("private final List<Car> items;")
                .contains("this.items = List.copyOf(items);")
                .contains("public static Cars empty()")
                .contains("public static Cars of(Car... items)")
                .contains("public static Cars of(Collection<Car> items)")
                .contains("public List<Car> toList()")
                .contains("public List<Car> elements()")
                .contains("public Set<Car> toSet()")
                .contains("public Cars plus(Car item)")
                .contains("public Cars plusAll(Iterable<Car> others)")
                .contains("public Cars minus(Car item)")
                .contains("public Cars filter(Predicate<Car> predicate)");
    }

    @Test
    @DisplayName("Generiert explizit benannte Collection mit Custom-Methoden")
    void shouldGenerateCustomCollectionWithMethods() {
        String src = """
                package com.example;
                value Money(BigDecimal amount) collection MoneyTransactions {
                    public Money sum() {
                        return null;
                    }
                };
                """;

        CompilationUnitNode cu = parse(src);
        ValueObjectNode node = cu.definitions().stream()
                .filter(d -> d instanceof ValueObjectNode)
                .map(d -> (ValueObjectNode) d)
                .findFirst().orElseThrow();

        JavaFile file = generator.generate(node, "com.example", List.of());
        String code = file.toString();

        assertThat(code)
                .contains("package com.example.domain;")
                .contains("public final class MoneyTransactions implements Iterable<Money>")
                .contains("public Money sum()");
    }

    @Test
    @DisplayName("Generiert Entity-Collection mit minusId(id)")
    void shouldGenerateEntityCollectionWithMinusId() {
        String src = """
                package com.example;
                id TaskId;
                entity Task[TaskId](String title) collection;
                """;

        CompilationUnitNode cu = parse(src);
        EntityNode entity = cu.definitions().stream()
                .filter(d -> d instanceof EntityNode)
                .map(d -> (EntityNode) d)
                .findFirst().orElseThrow();

        JavaFile file = generator.generate(entity, "com.example", List.of());
        String code = file.toString();

        assertThat(code)
                .contains("package com.example.domain;")
                .contains("public final class Tasks implements Iterable<Task>")
                .contains("public Tasks plus(Task item)")
                .contains("public Tasks minus(Task item)")
                .contains("public Tasks minusId(TaskId id)")
                .contains("return new Tasks(this.items.stream().filter(item -> !item.id().equals(id)).toList());");
    }

    @Test
    @DisplayName("Berücksichtigt benutzerdefiniertes Package für Entity-Collection")
    void shouldRespectCustomPackageForEntityCollection() {
        String src = """
                package com.example;
                id LineId;
                entity OrderLine[LineId](String sku) {
                    package com.example.mycustom.order;
                } collection OrderLines;
                """;

        CompilationUnitNode cu = parse(src);
        EntityNode entity = cu.definitions().stream()
                .filter(d -> d instanceof EntityNode)
                .map(d -> (EntityNode) d)
                .findFirst().orElseThrow();

        assertThat(entity.customPackage()).contains("com.example.mycustom.order");
        JavaFile file = generator.generate(entity, "com.example", List.of());
        String code = file.toString();

        assertThat(code)
                .contains("package com.example.mycustom.order;")
                .contains("public final class OrderLines implements Iterable<OrderLine>");
    }
}