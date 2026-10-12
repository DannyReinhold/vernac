// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.generator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;

class EntityGeneratorTest {

    private String generated(String source, String name) {
        var result = new org.vernac.compiler.pipeline.VernacCompiler().compileSource(source);
        var compiled = org.vernac.compiler.testutil.InMemoryJavaCompiler.compile(result.generatedFiles().stream()
                .filter(f -> !f.packageName().contains(".adapter.")).toList());
        assertThat(compiled.success()).as(compiled.diagnostics().toString()).isTrue();
        return result.generatedFiles().stream().filter(f -> f.typeSpec().name().equals(name))
                .findFirst().orElseThrow().toString();
    }

    @Test
    @DisplayName("Generiert Entity mit Entity-Interface, mut-Settern, create/reconstitute und ID-Semantik")
    void shouldGenerateEntity() {
        String src = """
                namespace com.example;
                
                id TaskId; value TaskTitle(String); value TaskStatus = PENDING | COMPLETED;
                entity Task[TaskId](TaskTitle title, mut TaskStatus status) validates {
                    require(self.title().string().length() <= 100, "Title too long");
                } behavior {
                    modify void complete() {
                        self.status(TaskStatus.COMPLETED);
                    }
                };
                """;

        String code = generated(src, "Task");
        String normalizedCode = code.replaceAll("\\s+", " ");

        assertThat(code)
                .contains("public class Task implements Entity<TaskId>")
                .contains("private final TaskId id;")
                .contains("private final TaskTitle title;")
                .contains("private TaskStatus status;")
                .contains("public void status(TaskStatus status)")
                .contains("validate();")
                .contains("public void complete()")
                .contains("public TaskId id()")
                .contains("public TaskTitle title()")
                .contains("public TaskStatus status()")
                .contains("return Objects.equals(this.id, that.id);")
                .contains("return Objects.hash(this.id);")
                .contains("return \"Task[id=\" + this.id + \", title=\" + this.title + \", status=\" + this.status + \"]\";");

        assertThat(normalizedCode)
                .contains("public static Task create(TaskTitle title, TaskStatus status)")
                .contains("public static Task reconstitute(TaskId id, TaskTitle title, TaskStatus status)");
    }

    @Test
    @DisplayName("Nutzt konsistent explizite und abgeleitete Feldnamen im generierten Entity durch alle Methoden")
    void shouldGenerateEntityWithExplicitAndDerivedNames() {
        String src = """
                namespace com.example;
                id ItemId; value Description(String); value Quantity(int);
                entity OrderItem[ItemId](Description, mut Quantity explicitQuantity);
                """;

        String code = generated(src, "OrderItem").replaceAll("\\s+", " ");

        // Prüft, ob Default-Name ('string') und Custom-Name ('explicitQuantity') korrekt deklariert werden
        assertThat(code)
                .contains("private final Description description;")
                .contains("private Quantity explicitQuantity;");

        // Prüft die Signaturen der Factory-Methoden
        assertThat(code)
                .contains("public static OrderItem create(Description description, Quantity explicitQuantity)")
                .contains("public static OrderItem reconstitute(ItemId id, Description description, Quantity explicitQuantity)");

        // Prüft die internen Mutatoren (Setter) für mut-Felder
        assertThat(code)
                .contains("public void explicitQuantity(Quantity explicitQuantity)")
                .contains("OrderItem.this.explicitQuantity = explicitQuantity;");

        // Prüft die öffentlichen Getter
        assertThat(code)
                .contains("public Description description()")
                .contains("public Quantity explicitQuantity()");
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
                entity OrderItem[ItemId](Description description, Price unitPrice); aggregate Project[ProjectId](OrderItem); repository Projects for Project {}
                """;
        assertThat(generated(src, "OrderItem")).doesNotContain("TABLE_NAME", "SCHEMA_DDL");
        var source = root.resolve("com/example/model.vernac");
        java.nio.file.Files.createDirectories(source.getParent());
        java.nio.file.Files.writeString(source, src);
        var project = new org.vernac.compiler.pipeline.VernacCompiler().analyzeProject(root);
        var schema = new org.vernac.compiler.persistence.SchemaBuilder(project,
                org.vernac.compiler.persistence.SchemaModel.empty()).build();
        var table = schema.tables().stream().filter(t -> t.name().equals("Project.@entity:com.example.OrderItem")).findFirst().orElseThrow();
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
            entity Counter[CounterId](mut Count count) behavior {
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