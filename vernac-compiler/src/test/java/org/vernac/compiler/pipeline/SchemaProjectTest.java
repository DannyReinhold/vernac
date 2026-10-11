// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.dto.SchemaContracts;
import static org.assertj.core.api.Assertions.*;
class SchemaProjectTest {
    @TempDir Path root;
    private void source(String schemas) throws Exception {
        Path dir=Files.createDirectories(root.resolve("sample"));
        Files.writeString(dir.resolve("model.vernac"),"namespace sample; port Remote { void ping(); } adapter RemoteClient implements Remote { behavior { public void ping() {} } "+schemas+" }");
    }
    @Test void resolvesLocalForwardReferencesWithoutDomainImports() throws Exception {
        source("schema Response(list<Item> items, int? count); schema Item(String sku);");
        var generated=new VernacCompiler().compileProject(root).generatedFiles().toString();
        assertThat(generated).contains("record Response", "Optional<Integer>", "List<Item>", "RemoteClientDtos");
    }
    @Test void rejectsAmbiguityAndInvalidShapesBeforeJavac() throws Exception {
        source("schema Response naming snake_case(String fooBar, String foo_bar, int intValue, int, String toString, String? comment absent as null, patch String? bad absent as null, list<String?> words, domain.Value unknown);");
        assertThatThrownBy(()->new VernacCompiler().compileProject(root)).hasMessageContaining("Duplicate external JSON field name")
            .hasMessageContaining("Duplicate DTO field").hasMessageContaining("generated Java API")
            .hasMessageContaining("not a PATCH field").hasMessageContaining("cannot be optional").hasMessageContaining("Unknown DTO type");
    }
    @Test void rejectsCyclesAndReservedSchemaNames() throws Exception {
        source("schema A(list<B> children); schema B(A? parent); schema String();");
        assertThatThrownBy(()->new VernacCompiler().compileProject(root)).hasMessageContaining("Cyclic DTO containment").hasMessageContaining("Reserved DTO schema name");
    }
    @Test void validatesNamingStrategyAndExplicitWireCollisions() throws Exception {
        source("schema Response naming guessed(String first named \"same\", String second named \"same\");");
        assertThatThrownBy(()->new VernacCompiler().compileProject(root)).hasMessageContaining("exact or snake_case").hasMessageContaining("Duplicate external JSON field name");
    }
    @Test void javaDtoTypesAndAccessorsNavigateBackToTheirLocalSchema() throws Exception {
        source("schema Response(String requestId);");
        Path file=root.resolve("sample/model.vernac");String text=Files.readString(file);
        var project=new VernacCompiler().readProject(root);
        var navigation=new org.vernac.compiler.tooling.VernacTypeNavigation();
        String javaName="sample.adapter.outbound.dto.RemoteClientDtos.Response";
        assertThat(navigation.find(project,java.util.Map.of(file,text),javaName)).isPresent();
        assertThat(navigation.findMember(project,java.util.Map.of(file,text),javaName,
            new org.vernac.compiler.tooling.VernacTypeNavigation.Member("requestId",java.util.List.of()))).isPresent();
    }
    @Test void namingIsExplicitAndLocaleIndependent() {
        assertThat(SchemaContracts.snakeCase("trackingNumber")).isEqualTo("tracking_number");
        assertThat(SchemaContracts.snakeCase("URLValue")).isEqualTo("url_value");
        assertThat(SchemaContracts.snakeCase("HTML2XMLMapper")).isEqualTo("html2_xml_mapper");
        assertThat(SchemaContracts.snakeCase("MaßeGröße")).isEqualTo("maße_größe");
    }
}
