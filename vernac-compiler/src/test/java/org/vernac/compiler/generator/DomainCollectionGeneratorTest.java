package org.vernac.compiler.generator;

import com.squareup.javapoet.JavaFile;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vernac.compiler.ast.AstBuilderVisitor;
import org.vernac.compiler.ast.CompilationUnitNode;
import org.vernac.compiler.ast.ValueObjectNode;
import org.vernac.compiler.parser.VernacLexer;
import org.vernac.compiler.parser.VernacParser;

import static org.assertj.core.api.Assertions.assertThat;

class DomainCollectionGeneratorTest {

    private final DomainCollectionGenerator generator = new DomainCollectionGenerator();

    private CompilationUnitNode parse(String source) {
        VernacLexer lexer = new VernacLexer(CharStreams.fromString(source));
        VernacParser parser = new VernacParser(new CommonTokenStream(lexer));
        return new AstBuilderVisitor().visitCompilationUnit(parser.compilationUnit());
    }

    @Test
    @DisplayName("Generiert Standard-Collection mit pluralisiertem Namen und Immutability")
    void shouldGenerateDefaultPluralizedCollection() {
        String src = """
                package com.example.domain;
                value Car(String model);
                """;

        CompilationUnitNode cu = parse(src);
        ValueObjectNode node = cu.valueObjects().getFirst();
        JavaFile file = generator.generate(node, "com.example.domain");
        String code = file.toString();

        assertThat(code)
                .contains("public final class Cars implements Iterable<Car>")
                .contains("private final List<Car> items;")
                .contains("this.items = List.copyOf(items);")
                .contains("public static Cars empty()")
                .contains("public static Cars of(Car... items)")
                .contains("public static Cars of(Collection<Car> items)")
                .contains("public List<Car> toList()")
                .contains("public Set<Car> toSet()")
                .contains("return Set.copyOf(this.items);")
                .contains("public Cars filter(Predicate<Car> predicate)");
    }

    @Test
    @DisplayName("Generiert explizit benannte Collection mit Custom-Methoden")
    void shouldGenerateCustomCollectionWithMethods() {
        String src = """
                package com.example.domain;
                value Money(BigDecimal amount) collection MoneyTransactions {
                    public Money sum() {
                        return null;
                    }
                };
                """;

        CompilationUnitNode cu = parse(src);
        ValueObjectNode node = cu.valueObjects().getFirst();
        JavaFile file = generator.generate(node, "com.example.domain");
        String code = file.toString();

        assertThat(code)
                .contains("public final class MoneyTransactions implements Iterable<Money>")
                .contains("public Money sum()");
    }
}