// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.generator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;

class AggregateGeneratorTest {

    private String generated(String source, String name) {
        var result = new org.vernac.compiler.pipeline.VernacCompiler().compileSource(source);
        var compiled = org.vernac.compiler.testutil.InMemoryJavaCompiler.compile(result.generatedFiles().stream()
                .filter(f -> !f.packageName().contains(".adapter.")).toList());
        assertThat(compiled.success()).as(compiled.diagnostics().toString()).isTrue();
        return result.generatedFiles().stream().filter(f -> f.typeSpec().name().equals(name))
                .findFirst().orElseThrow().toString();
    }

    @Test
    @DisplayName("Generiert vollständiges Aggregate Root mit Lifecycle, mut-Settern, Events und ID-Semantik")
    void shouldGenerateAggregateRoot() {
        String src = """
                namespace com.example;
                
                id ProjectId; value ProjectName(String); value Money(BigDecimal amount);
                aggregate Project[ProjectId](ProjectName name, mut Money budget) validates {
                    require(self.budget().amount().signum() >= 0, "Budget cannot be negative");
                } behavior {
                    modify void assignBudget(Money newBudget) {
                        self.budget(newBudget);
                    }
                };
                """;

        String code = generated(src, "Project");
        String normalizedCode = code.replaceAll("\\s+", " ");

        assertThat(code)
                .contains("public class Project implements AggregateRoot<ProjectId>")
                .contains("private final Instant createdAt;")
                .contains("private Instant updatedAt;")
                .contains("private final PersistenceState persistenceState;")
                .contains("private final ProjectId id;")
                .contains("private final ProjectName name;")
                .contains("private Money budget;")
                .contains("public void budget(Money budget)")
                .contains("BehaviorModification.changed(Project.this)")
                .contains("public void assignBudget(Money newBudget)")
                .contains("public ProjectId id()")
                .contains("public Instant createdAt()")
                .contains("public Instant updatedAt()")
                .contains("public PersistenceState persistenceState()")
                .contains("return Objects.equals(this.id, that.id);")
                .contains("return Objects.hash(this.id);")
                .contains("return \"Project[id=\" + this.id + \", name=\" + this.name + \", budget=\" + this.budget + \", createdAt=\" + this.createdAt + \", updatedAt=\" + this.updatedAt + \"]\";");

        assertThat(normalizedCode)
                .contains("public static Project create(ProjectName name, Money budget)")
                .contains("public static Project reconstitute(ProjectId id, ProjectName name, Money budget, Instant createdAt, Instant updatedAt, long version)");
    }

    @Test
    @DisplayName("Publishes explicitly through modify Access, independently of repository save")
    void shouldGenerateAggregateWithEventEmitting() {
        var result = new org.vernac.compiler.pipeline.VernacCompiler().compileSource("""
                namespace example;
                id ProjectId; value ProjectName(String);
                event ProjectRenamed(ProjectId, ProjectName);
                aggregate Project[ProjectId](mut ProjectName name) behavior {
                    modify void rename(ProjectName newName) {
                        self.name(newName);
                        self.emit(ProjectRenamed.create(self.id(), newName));
                    }
                };
                """);
        String code=result.generatedFiles().stream().map(Object::toString).reduce("",String::concat);
        assertThat(code).contains("public void rename(ProjectName newName)", "self.name(newName)",
                "self.emit(ProjectRenamed.create(self.id(), newName))", "EventContext.emit(event)")
                .doesNotContain("registerEvent", "pullDomainEvents", "domainEvents");
        var compiled=org.vernac.compiler.testutil.InMemoryJavaCompiler.compile(result.generatedFiles());
        assertThat(compiled.success()).withFailMessage(compiled.diagnostics().toString()).isTrue();
    }

    @Test
    @DisplayName("Nutzt konsistent explizite und abgeleitete Feldnamen inkl. Mutatoren durch alle Methoden")
    void shouldGenerateAggregateWithExplicitAndDerivedNames() {
        String src = """
                namespace com.example;
                id CustomerId; value CustomerName(String);
                aggregate Customer[CustomerId](CustomerName, mut CustomerName customAlias);
                """;

        String code = generated(src, "Customer").replaceAll("\\s+", " ");

        assertThat(code)
                .contains("private final CustomerName customerName;")
                .contains("private CustomerName customAlias;")
                .contains("private Customer(CustomerId id, CustomerName customerName, CustomerName customAlias, Instant createdAt, Instant updatedAt, long version)")
                .contains("public static Customer create(CustomerName customerName, CustomerName customAlias)")
                .contains("public static Customer reconstitute(CustomerId id, CustomerName customerName, CustomerName customAlias, Instant createdAt, Instant updatedAt, long version)")
                .contains("public void customAlias(CustomerName customAlias) {")
                .contains("Customer.this.customAlias = customAlias;")
                .contains("public CustomerName customerName() { return this.customerName; }")
                .contains("public CustomerName customAlias() { return this.customAlias; }");
    }

    @Test
    @DisplayName("Keeps domain classes storage-free; explicit repositories flatten nested values")
    void shouldGenerateFlattenedSchemaOutsideTheDomain(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        String src = """
                namespace com.example;
                id ProjectId; id ItemId;
                value Description(String);
                value CurrencyCode(String);
                value Price(BigDecimal amount, CurrencyCode currency);
                aggregate Project[ProjectId](Description description, Price unitPrice); repository Projects for Project {}
                """;
        assertThat(generated(src, "Project")).doesNotContain("TABLE_NAME", "SCHEMA_DDL");
        var source = root.resolve("com/example/model.vernac");
        java.nio.file.Files.createDirectories(source.getParent());
        java.nio.file.Files.writeString(source, src);
        var project = new org.vernac.compiler.pipeline.VernacCompiler().analyzeProject(root);
        var schema = new org.vernac.compiler.persistence.SchemaBuilder(project,
                org.vernac.compiler.persistence.SchemaModel.empty()).build();
        var table = schema.tables().stream().filter(t -> t.name().equals("Project")).findFirst().orElseThrow();
        assertThat(table.columns()).extracting(org.vernac.compiler.persistence.SchemaModel.Column::name)
                .contains("id", "description", "unitPrice.amount", "@scale:unitPrice.amount", "unitPrice.currency");
        assertThat(table.columns()).filteredOn(c -> c.name().equals("unitPrice.amount"))
                .extracting(org.vernac.compiler.persistence.SchemaModel.Column::sqlType).containsExactly("NUMERIC");
    }

    @Test
    void generatesBehaviorViewsWithoutExposingSetters() {
        var result = new org.vernac.compiler.pipeline.VernacCompiler().compileSource("""
            namespace access.example;
            id CounterId; value Count(int);
            aggregate Counter[CounterId](mut Count count) behavior {
                read int current() { return self.count().intValue(); }
                modify void increase() { self.count(Count.of(self.count().intValue() + 1)); }
            };
            """);
        String owner = result.generatedFiles().stream().filter(f -> f.typeSpec().name().equals("Counter")).findFirst().orElseThrow().toString();
        assertThat(owner).contains("private class __ReadView", "private final class __AccessView", "implements CounterRead", "implements CounterAccess");
        assertThat(owner.substring(owner.indexOf("public class Counter"), owner.indexOf("{", owner.indexOf("public class Counter")))).doesNotContain("CounterRead", "CounterAccess");
        assertThat(result.generatedFiles()).anyMatch(f -> f.typeSpec().name().equals("CounterWrite"));
        var compiled = org.vernac.compiler.testutil.InMemoryJavaCompiler.compile(result.generatedFiles().stream()
                .filter(f -> !f.packageName().contains(".adapter.")).toList());
        assertThat(compiled.success()).as(compiled.diagnostics().toString()).isTrue();
    }
}