// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.generator;

import com.palantir.javapoet.JavaFile;
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
                namespace com.example;
                value Car(String model) list;
                """;

        CompilationUnitNode cu = parse(src);
        ValueObjectNode node = cu.definitions().stream()
                .filter(d -> d instanceof ValueObjectNode)
                .map(d -> (ValueObjectNode) d)
                .findFirst().orElseThrow();

        JavaFile file = new org.vernac.compiler.pipeline.VernacCompiler().compileSource(src).generatedFiles().stream()
                .filter(f -> f.typeSpec().name().equals(node.collection().orElseThrow().nameFor(node.name()))).findFirst().orElseThrow();
        String code = file.toString();

        assertThat(code)
                .contains("package com.example.domain;")
                .contains("public final class Cars implements Iterable<Car>")
                .contains("private final List<Car> items;")
                .contains("this.items = DomainCollections.list(items);")
                .contains("public static Cars empty()")
                .contains("public static Cars of(Car... items)")
                .contains("public static Cars of(Iterable<? extends Car> items)")
                .contains("public List<Car> asList()")
                .contains("public Cars plus(Car element)")
                .contains("public Cars plusAll(Iterable<? extends Car> elements)")
                .contains("public Cars minus(Car element)")
                .contains("public Cars filter(Predicate<? super Car> predicate)");
    }

    @Test
    @DisplayName("Generiert explizit benannte Collection mit Custom-Methoden")
    void shouldGenerateCustomCollectionWithMethods() {
        String src = """
                namespace com.example;
                value Money(BigDecimal amount) list MoneyTransactions {
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

        JavaFile file = new org.vernac.compiler.pipeline.VernacCompiler().compileSource(src).generatedFiles().stream()
                .filter(f -> f.typeSpec().name().equals(node.collection().orElseThrow().nameFor(node.name()))).findFirst().orElseThrow();
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
                namespace com.example;
                id TaskId;
                entity Task[TaskId](String title) list;
                """;

        CompilationUnitNode cu = parse(src);
        EntityNode entity = cu.definitions().stream()
                .filter(d -> d instanceof EntityNode)
                .map(d -> (EntityNode) d)
                .findFirst().orElseThrow();

        JavaFile file = new org.vernac.compiler.pipeline.VernacCompiler().compileSource(src).generatedFiles().stream()
                .filter(f -> f.typeSpec().name().equals(entity.collection().orElseThrow().nameFor(entity.name()))).findFirst().orElseThrow();
        String code = file.toString();

        assertThat(code)
                .contains("package com.example.domain;")
                .contains("public final class Tasks implements Iterable<Task>")
                .contains("public Tasks plus(Task element)")
                .contains("public Tasks minus(Task element)")
                .contains("public Tasks minusId(TaskId id)")
                .contains("return by(id).map(this::minus).orElse(this);");
    }

    @Test
    @DisplayName("Verhindert benutzerdefiniertes Package für Entity-Collection")
    void shouldRejectCustomPackageForEntityCollection() {
        String src = """
                namespace com.example;
                id LineId;
                entity OrderLine[LineId](String sku) {
                    package com.example.mycustom.order;
                } list OrderLines;
                """;

        CompilationUnitNode cu = parse(src);
        EntityNode entity = cu.definitions().stream()
                .filter(d -> d instanceof EntityNode)
                .map(d -> (EntityNode) d)
                .findFirst().orElseThrow();

        assertThat(entity.customPackage()).contains("com.example.mycustom.order");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new org.vernac.compiler.pipeline.VernacCompiler().compileSource(src))
                .isInstanceOf(org.vernac.compiler.analyzer.SemanticValidationException.class)
                .hasMessageContaining("custom packages");
    }
}
