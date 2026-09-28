package org.vernac.compiler.generator;

import com.squareup.javapoet.JavaFile;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vernac.compiler.ast.AstBuilderVisitor;
import org.vernac.compiler.ast.CompilationUnitNode;
import org.vernac.compiler.ast.EventNode;
import org.vernac.compiler.parser.VernacLexer;
import org.vernac.compiler.parser.VernacParser;

import static org.assertj.core.api.Assertions.assertThat;

class EventGeneratorTest {

    private final EventGenerator generator = new EventGenerator();

    private CompilationUnitNode parse(String source) {
        VernacLexer lexer = new VernacLexer(CharStreams.fromString(source));
        VernacParser parser = new VernacParser(new CommonTokenStream(lexer));
        return new AstBuilderVisitor().visitCompilationUnit(parser.compilationUnit());
    }

    @Test
    @DisplayName("Generiert Domain Event mit create(), of(), @Dispatch, Konstante und Getter-Zugriff in toString()")
    void shouldGenerateDomainEventWithCreateAndDispatch() {
        String src = """
                package com.example.domain;
                
                @Dispatch(Outbox)
                event ProjectBudgetExceeded(ProjectId projectId, Money currentCost, String? reason);
                """;

        CompilationUnitNode cu = parse(src);
        EventNode node = cu.events().getFirst();
        JavaFile file = generator.generate(node, "com.example.domain");
        String code = file.toString();
        String normalizedCode = code.replaceAll("\\s+", " ");

        assertThat(code)
                .contains("@Dispatch(DispatchMode.OUTBOX)")
                .contains("public final class ProjectBudgetExceeded implements DomainEvent")
                .contains("public static final String EVENT_TYPE = \"PROJECT_BUDGET_EXCEEDED\";")
                .contains("private final UUID eventId;")
                .contains("private final Instant occurredOn;")
                .contains("private final ProjectId projectId;")
                .contains("@Nullable")
                .contains("private final String reason;")
                .contains("public UUID eventId()")
                .contains("public Instant occurredOn()")
                .contains("public String eventType()")
                .contains("public Optional<String> reason()")
                .contains("return Objects.equals(this.eventId, that.eventId);")
                .contains("reason=\" + this.reason().orElse(null)");

        // Umbruch-unabhängige Prüfung für lange Methodensignaturen
        assertThat(normalizedCode)
                .contains("public static ProjectBudgetExceeded create(ProjectId projectId, Money currentCost, @Nullable String reason)")
                .contains("public static ProjectBudgetExceeded create(ProjectId projectId, Money currentCost)")
                .contains("return create(projectId, currentCost, null);")
                .contains("public static ProjectBudgetExceeded of(UUID eventId, Instant occurredOn, ProjectId projectId, Money currentCost, @Nullable String reason)");
    }
}