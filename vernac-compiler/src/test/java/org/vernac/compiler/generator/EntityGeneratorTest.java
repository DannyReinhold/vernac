// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.generator;

import com.palantir.javapoet.JavaFile;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.parser.VernacLexer;
import org.vernac.compiler.parser.VernacParser;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EntityGeneratorTest {

    private final EntityGenerator generator = new EntityGenerator();

    private CompilationUnitNode parse(String source) {
        VernacLexer lexer = new VernacLexer(CharStreams.fromString(source));
        VernacParser parser = new VernacParser(new CommonTokenStream(lexer));
        return new AstBuilderVisitor().visitCompilationUnit(parser.compilationUnit());
    }

    private Map<String, ValueObjectNode> extractValueObjects(CompilationUnitNode cu) {
        Map<String, ValueObjectNode> map = new HashMap<>();
        for (TopLevelDefinition def : cu.definitions()) {
            if (def instanceof ValueObjectNode vo) {
                map.put(vo.name(), vo);
            }
        }
        return map;
    }

    @Test
    @DisplayName("Generiert Entity mit Entity-Interface, mut-Settern, create/reconstitute und ID-Semantik")
    void shouldGenerateEntity() {
        String src = """
                namespace com.example.domain;
                
                entity Task[TaskId](TaskTitle title, mut TaskStatus status) validates {
                    require(title.value().length() <= 100, "Title too long");
                } {
                    public void complete() {
                        status(TaskStatus.COMPLETED);
                    }
                };
                """;

        CompilationUnitNode cu = parse(src);
        EntityNode node = cu.entities().getFirst();
        JavaFile file = generator.generate(node, Map.of(), Map.of(), "com.example.domain", List.of());
        String code = file.toString();
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
                .contains("return \"Task[id=\" + this.id + \"]\";");

        assertThat(normalizedCode)
                .contains("public static Task create(TaskTitle title, TaskStatus status)")
                .contains("public static Task reconstitute(TaskId id, TaskTitle title, TaskStatus status)");
    }

    @Test
    @DisplayName("Nutzt konsistent explizite und abgeleitete Feldnamen im generierten Entity durch alle Methoden")
    void shouldGenerateEntityWithExplicitAndDerivedNames() {
        String src = """
                namespace com.example.domain;
                entity OrderItem[ItemId](String, mut int explicitQuantity);
                """;

        CompilationUnitNode cu = parse(src);
        EntityNode node = cu.entities().getFirst();
        JavaFile file = generator.generate(node, Map.of(), Map.of(), "com.example.domain", List.of());
        String code = file.toString().replaceAll("\\s+", " ");

        // Prüft, ob Default-Name ('string') und Custom-Name ('explicitQuantity') korrekt deklariert werden
        assertThat(code)
                .contains("private final String string;")
                .contains("private int explicitQuantity;");

        // Prüft die Signaturen der Factory-Methoden
        assertThat(code)
                .contains("public static OrderItem create(String string, int explicitQuantity)")
                .contains("public static OrderItem reconstitute(ItemId id, String string, int explicitQuantity)");

        // Prüft die internen Mutatoren (Setter) für mut-Felder
        assertThat(code)
                .contains("public void explicitQuantity(int explicitQuantity)")
                .contains("this.explicitQuantity = explicitQuantity;");

        // Prüft die öffentlichen Getter
        assertThat(code)
                .contains("public String string()")
                .contains("public int explicitQuantity()");
    }

    @Test
    @DisplayName("Erzeugt TABLE_NAME und SCHEMA_DDL mit korrektem Value-Object-Flattening für Entities")
    void shouldGenerateSchemaConstantsWithFlattenedColumns() {
        String src = """
                namespace com.example.domain;
                
                value ItemDescription(String text);
                value Currency(String isoCode);
                value Price(BigDecimal amount, Currency currency);
                
                entity OrderItem[ItemId](ItemDescription description, Price unitPrice);
                """;

        CompilationUnitNode cu = parse(src);
        Map<String, ValueObjectNode> valueObjects = extractValueObjects(cu);
        EntityNode node = cu.entities().getFirst();

        JavaFile file = generator.generate(node, valueObjects, Map.of(), "com.example.domain", List.of());
        String code = file.toString();

        assertThat(code)
                .contains("public static final String TABLE_NAME = \"order_item\";")
                .contains("public static final String SCHEMA_DDL =")
                .contains("CREATE TABLE IF NOT EXISTS order_item (")
                .contains("id UUID PRIMARY KEY")
                .contains("description VARCHAR(255) NOT NULL")
                .contains("unit_price_amount NUMERIC(19, 4) NOT NULL")
                .contains("unit_price_currency VARCHAR(255) NOT NULL");
    }
}