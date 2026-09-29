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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ValueObjectGeneratorTest {

    private final ValueObjectGenerator generator = new ValueObjectGenerator();

    private CompilationUnitNode parse(String source) {
        VernacLexer lexer = new VernacLexer(CharStreams.fromString(source));
        VernacParser parser = new VernacParser(new CommonTokenStream(lexer));
        return new AstBuilderVisitor().visitCompilationUnit(parser.compilationUnit());
    }

    @Test
    @DisplayName("Generiert Single-Value Object mit create(), asString(), asUuid()")
    void shouldGenerateSingleValueObjectWithHelpers() {
        String src = """
                package com.example.domain;
                value ProjectId(UUID value);
                """;

        CompilationUnitNode cu = parse(src);
        ValueObjectNode node = cu.valueObjects().getFirst();
        JavaFile file = generator.generate(node, "com.example.domain", List.of());
        String code = file.toString();

        assertThat(code)
                .contains("public final class ProjectId implements ValueObject")
                .contains("private final UUID value;")
                .contains("private ProjectId(UUID value)")
                .contains("this.value = Objects.requireNonNull(value, \"value must not be null\")")
                .contains("public static ProjectId of(UUID value)")
                .contains("public static ProjectId of(String value)")
                .contains("public static ProjectId create()")
                .contains("public UUID value()")
                .contains("public String asString()")
                .contains("public UUID asUuid()");
    }

    @Test
    @DisplayName("Generiert Multi-Value Object mit @Nullable und Optional Getter")
    void shouldGenerateMultiValueObjectWithNullableAndOptional() {
        String src = """
                package com.example.domain;
                value Money(BigDecimal amount, String? comment) validates {
                    require(amount.compareTo(BigDecimal.ZERO) >= 0, "Amount must be positive");
                };
                """;

        CompilationUnitNode cu = parse(src);
        ValueObjectNode node = cu.valueObjects().getFirst();
        JavaFile file = generator.generate(node, "com.example.domain", List.of());
        String code = file.toString();

        assertThat(code)
                .contains("public final class Money implements ValueObject")
                .contains("private final BigDecimal amount;")
                .contains("@Nullable")
                .contains("private final String comment;")
                .contains("public Optional<String> comment()")
                .contains("return Optional.ofNullable(this.comment);")
                .contains("private void validate()")
                .contains("throw new DomainValidationException(\"Amount must be positive\")")
                .contains("public static Money of(BigDecimal amount)")
                .contains("public static Money of(BigDecimal amount, @Nullable String comment)");
    }

    @Test
    @DisplayName("Generiert konsistent explizite Feldnamen (Felder, Konstruktor, Getter, Factories)")
    void shouldGenerateCodeWithExplicitFieldNames() {
        String src = """
                package com.example.domain;
                value UserEmail(String emailAddress, boolean isVerified);
                """;

        CompilationUnitNode cu = parse(src);
        ValueObjectNode node = cu.valueObjects().getFirst();
        JavaFile file = generator.generate(node, "com.example.domain", java.util.List.of());
        String code = file.toString().replaceAll("\\s+", " ");

        assertThat(code)
                .contains("private final String emailAddress;")
                .contains("private final boolean isVerified;")
                .contains("private UserEmail(String emailAddress, boolean isVerified)")
                .contains("this.emailAddress = Objects.requireNonNull(emailAddress, \"emailAddress must not be null\")")
                .contains("public static UserEmail of(String emailAddress, boolean isVerified)")
                .contains("public String emailAddress() { return this.emailAddress; }")
                .contains("public boolean isVerified() { return this.isVerified; }");
    }

    @Test
    @DisplayName("Leitet Namen aus Typen ab, wenn mehrere Felder ohne Namen angegeben sind")
    void shouldDeriveFieldNamesFromTypes() {
        String src = """
                package com.example.domain;
                value Document(String, UUID);
                """;

        CompilationUnitNode cu = parse(src);
        ValueObjectNode node = cu.valueObjects().getFirst();
        JavaFile file = generator.generate(node, "com.example.domain", java.util.List.of());
        String code = file.toString().replaceAll("\\s+", " ");

        // String -> string, UUID -> uuid
        assertThat(code)
                .contains("private final String string;")
                .contains("private final UUID uuid;")
                .contains("private Document(String string, UUID uuid)")
                .contains("public static Document of(String string, UUID uuid)")
                .contains("public String string()")
                .contains("public UUID uuid()");
    }
}