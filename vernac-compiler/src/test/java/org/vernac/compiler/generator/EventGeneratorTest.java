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
import org.vernac.compiler.ast.EventNode;
import org.vernac.compiler.parser.VernacLexer;
import org.vernac.compiler.parser.VernacParser;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventGeneratorTest {

    private final EventGenerator generator = new EventGenerator();

    private CompilationUnitNode parse(String source) {
        VernacLexer lexer = new VernacLexer(CharStreams.fromString(source));
        VernacParser parser = new VernacParser(new CommonTokenStream(lexer));
        return new AstBuilderVisitor().visitCompilationUnit(parser.compilationUnit());
    }

    @Test
    @DisplayName("Generiert Domain Event mit create(), of(), DispatchMode, Konstante und Getter-Zugriff")
    void shouldGenerateDomainEventWithCreateAndDispatch() {
        String src = """
                namespace com.example.domain;
                
                outbox event ProjectBudgetExceeded(ProjectId projectId, Money currentCost, String? reason);
                """;

        CompilationUnitNode cu = parse(src);
        EventNode node = cu.events().getFirst();
        JavaFile file = generator.generate(node, "com.example.domain", List.of());
        String code = file.toString();
        String normalizedCode = code.replaceAll("\\s+", " ");

        assertThat(code)
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

        assertThat(normalizedCode)
                .contains("public static ProjectBudgetExceeded create(ProjectId projectId, Money currentCost, @Nullable String reason)")
                .contains("public static ProjectBudgetExceeded create(ProjectId projectId, Money currentCost)")
                .contains("return create(projectId, currentCost, null);")
                .contains("public static ProjectBudgetExceeded of(UUID eventId, Instant occurredOn, ProjectId projectId, Money currentCost, @Nullable String reason)");
    }

    @Test
    @DisplayName("Nutzt explizite und abgeleitete Feldnamen im generierten Event durchgängig")
    void shouldGenerateEventWithExplicitAndDerivedNames() {
        String src = """
                namespace com.example.domain;
                event CustomerRelocated(CustomerId, String newAddress);
                """;

        CompilationUnitNode cu = parse(src);
        EventNode node = cu.events().getFirst();
        JavaFile file = generator.generate(node, "com.example.domain", java.util.List.of());
        String code = file.toString().replaceAll("\\s+", " ");

        assertThat(code)
                .contains("private final CustomerId customerId;")
                .contains("private final String newAddress;")
                .contains("public static CustomerRelocated create(CustomerId customerId, String newAddress)")
                .contains("public static CustomerRelocated of(UUID eventId, Instant occurredOn, CustomerId customerId, String newAddress)")
                .contains("public CustomerId customerId()")
                .contains("public String newAddress()");
    }

    @Test
    @DisplayName("Erzeugt OUTBOX_TABLE_NAME und OUTBOX_SCHEMA_DDL nur bei Outbox-Events")
    void shouldGenerateOutboxSchemaConstantsForOutboxEvents() {
        String src = """
                namespace com.example.domain;
                
                outbox event OrderPlaced(String orderId);
                memory event OrderValidated(String orderId);
                """;

        CompilationUnitNode cu = parse(src);

        // 1. Outbox Event
        EventNode outboxNode = cu.events().get(0);
        JavaFile outboxFile = generator.generate(outboxNode, "com.example.domain", List.of());
        String outboxCode = outboxFile.toString();
        assertThat(outboxCode)
                .contains("public static final String OUTBOX_TABLE_NAME = \"vernac_outbox\";")
                .contains("public static final String OUTBOX_SCHEMA_DDL =")
                .contains("CREATE TABLE IF NOT EXISTS vernac_outbox (")
                .contains("<p>Expected PostgreSQL Outbox Table Schema:</p>");

        // 2. Memory Event (keine DDL-Konstanten)
        EventNode memoryNode = cu.events().get(1);
        JavaFile memoryFile = generator.generate(memoryNode, "com.example.domain", List.of());
        String memoryCode = memoryFile.toString();
        assertThat(memoryCode).doesNotContain("OUTBOX_TABLE_NAME");
        assertThat(memoryCode).doesNotContain("OUTBOX_SCHEMA_DDL");
    }
}