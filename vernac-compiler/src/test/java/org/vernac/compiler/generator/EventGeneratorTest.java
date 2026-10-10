// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.generator;

import org.junit.jupiter.api.Test;
import org.vernac.compiler.pipeline.VernacCompiler;
import static org.assertj.core.api.Assertions.*;

class EventGeneratorTest {
    private String generate(String source, String name) {
        return new VernacCompiler().compileSource("namespace demo; " + source).generatedFiles().stream()
                .filter(f -> f.typeSpec().name().equals(name)).findFirst().orElseThrow().toString().replaceAll("\\s+", " ");
    }
    @Test void shouldGenerateDomainEventWithCreateAndRestore() {
        String code=generate("id ProjectId; value Money(BigDecimal); value Reason(String); event BudgetExceeded(ProjectId, Money, Reason?);", "BudgetExceeded");
        assertThat(code).contains("public final class BudgetExceeded implements DomainEvent", "@NullMarked",
                "private final EventId eventId", "private final Instant occurredAt", "public EventId eventId()",
                "public Instant occurredAt()", "public Optional<Reason> reason()",
                "create(ProjectId projectId, Money money, @Nullable Reason reason)",
                "create(ProjectId projectId, Money money)", "return create(projectId, money, null)",
                "reconstitute(EventId eventId, Instant occurredAt, ProjectId projectId, Money money, @Nullable Reason reason)",
                "eventId.equals(that.eventId)", "return eventId.hashCode()", "reason=", "@Nullable Object other")
                .doesNotContain("DispatchMode", "SCHEMA_DDL", "EVENT_TYPE", "Serializable");
    }
    @Test void shouldGenerateEventWithExplicitAndDerivedNames() {
        assertThat(generate("id CustomerId; value Address(String); event Relocated(CustomerId, Address newAddress);", "Relocated"))
                .contains("private final CustomerId customerId", "private final Address newAddress",
                        "public CustomerId customerId()", "public Address newAddress()");
    }
    @Test void recordingAndDeliveryAreNotDomainConstants() {
        assertThat(generate("event Ping();", "Ping")).contains("create()", "reconstitute(EventId eventId, Instant occurredAt)")
                .doesNotContain("TABLE_NAME", "DDL", "OUTBOX");
        assertThatThrownBy(() -> generate("recorded event Ping();", "Ping")).hasMessageContaining("recorded events require");
    }
    @Test void rejectsMutableAndNonValuePayloadsAndReservedMetadata() {
        for(String fields: new String[]{"String text", "mut Title title", "Title eventId", "Title occurredAt"})
            assertThatThrownBy(() -> generate("value Title(String); event Changed("+fields+");", "Changed"));
        assertThatThrownBy(() -> generate("id ThingId; entity Thing[ThingId](); event Changed(Thing);", "Changed"))
                .hasMessageContaining("Event payloads require");
    }
    @Test void validatesThroughReadDelegate() {
        assertThat(generate("value Title(String); event Changed(Title) validates { require(!self.title().string().isBlank(), \"blank\"); };", "Changed"))
                .contains("__VernacValidation_Changed.validate(new __ReadView())");
    }
}
