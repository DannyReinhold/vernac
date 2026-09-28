package org.vernac.compiler.ast;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;
import org.vernac.compiler.parser.VernacLexer;
import org.vernac.compiler.parser.VernacParser;

import static org.assertj.core.api.Assertions.assertThat;

class AstBuilderVisitorTest {

    @Test
    void shouldParsePackageAndValueObjects() {
        String source = """
            package com.example.domain;
            
            value ProjectId(UUID value);
            value Money(BigDecimal amount, Currency currency);
            """;

        VernacLexer lexer = new VernacLexer(CharStreams.fromString(source));
        VernacParser parser = new VernacParser(new CommonTokenStream(lexer));

        AstBuilderVisitor visitor = new AstBuilderVisitor();
        CompilationUnitNode cu = visitor.visitCompilationUnit(parser.compilationUnit());

        assertThat(cu.packageName()).contains("com.example.domain");
        assertThat(cu.valueObjects()).hasSize(2);

        ValueObjectNode projectId = cu.valueObjects().getFirst();
        assertThat(projectId.name()).isEqualTo("ProjectId");
        assertThat(projectId.fields()).hasSize(1);
        assertThat(projectId.fields().getFirst().name()).isEqualTo("value");
        assertThat(projectId.fields().getFirst().type().name()).isEqualTo("UUID");

        ValueObjectNode money = cu.valueObjects().get(1);
        assertThat(money.name()).isEqualTo("Money");
        assertThat(money.fields()).hasSize(2);
        assertThat(money.fields().getFirst().name()).isEqualTo("amount");
        assertThat(money.fields().getFirst().type().name()).isEqualTo("BigDecimal");
        assertThat(money.fields().get(1).name()).isEqualTo("currency");
        assertThat(money.fields().get(1).type().name()).isEqualTo("Currency");
    }
}