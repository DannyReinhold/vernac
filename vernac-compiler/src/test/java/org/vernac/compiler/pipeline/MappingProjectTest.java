// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
class MappingProjectTest {
    @TempDir Path root;
    private void model(String mappings) throws Exception {
        Path dir=Files.createDirectories(root.resolve("demo"));
        Files.writeString(dir.resolve("model.vernac"),"namespace demo; value Name(String); value Pair(Name first, Name? second); port Remote { void ping(); } adapter Client implements Remote { schema Dto(String required, String? maybe, long number); schema Patch(patch String? text); schema StrictPatch(patch String text); behavior { public void ping() {} } "+mappings+" }");
    }
    @Test void optionalMissingIncompatibleAndUnknownFieldsAreCompilerErrors() throws Exception {
        model("mapping bad: Pair <- Dto { first <- source.maybe; second <- source.number; typo <- source.required; } mapping missing: Pair <- Dto {} mapping path: Pair <- Dto { first <- source.wrong; }");
        assertThatThrownBy(()->new VernacCompiler().compileProject(root)).hasMessageContaining("optional source")
            .hasMessageContaining("No exact conversion").hasMessageContaining("Unknown target field")
            .hasMessageContaining("Required target field").hasMessageContaining("Unknown source field");
    }
    @Test void mappingNamesAndInvocationGraphAreChecked() throws Exception {
        model("mapping a: Pair <- Pair { first <- source.first; second <- source.second; } mapping a: Pair <- Pair {} mapping b: Pair <- Pair { first <- source.first using noSuchMapping; }");
        assertThatThrownBy(()->new VernacCompiler().compileProject(root)).hasMessageContaining("Duplicate mapping name").hasMessageContaining("Unknown adapter-local mapping");
        model("mapping a: Name <- Name { string <- source using b; } mapping b: String <- Name { java { return source.string(); } }");
        assertThat(new VernacCompiler().compileProject(root).generatedFiles().toString()).contains("ClientMappings.b");
    }
    @Test void recursiveNamedMappingsAreRejected() throws Exception {
        model("mapping a: Name <- String { string <- source using b; } mapping b: String <- String { java { return source; } }");
        assertThat(new VernacCompiler().compileProject(root).generatedFiles()).isNotEmpty();
        model("mapping a: Name <- Dto { string <- source using a; }");
        assertThatThrownBy(()->new VernacCompiler().compileProject(root)).hasMessageContaining("incompatible source/target");
        model("mapping a: Name <- Dto { string <- source.required using b; } mapping b: Name <- String { string <- source; }");
        assertThatThrownBy(()->new VernacCompiler().compileProject(root)).hasMessageContaining("incompatible source/target");
    }
    @Test void mapsAcrossNamespacesAndProjectsJavaMappingBodies() throws Exception {
        model("mapping converted: Name <- String { java { return Name.of(source); } }");
        Path file=root.resolve("demo/model.vernac");var compiler=new VernacCompiler();var project=compiler.readProject(root);String text=Files.readString(file);
        var blocks=new org.vernac.compiler.tooling.BehaviorJavaProjection().build(project,project.sources().getFirst(),text);
        assertThat(blocks.toString()).contains("java.lang.String source", "converted(");
        var nav=new org.vernac.compiler.tooling.VernacTypeNavigation();
        assertThat(nav.findMember(project,java.util.Map.of(file,text),"demo.adapter.outbound.mapping.ClientMappings",
            new org.vernac.compiler.tooling.VernacTypeNavigation.Member("converted",java.util.List.of("java.lang.String")))).isPresent();
        Path second=Files.createDirectories(root.resolve("other"));
        Files.writeString(second.resolve("value.vernac"),"namespace other; value ExternalName(String);");
        text=text.replace("namespace demo;","namespace demo; import other.ExternalName;").replace("converted: Name <- String","converted: ExternalName <- String").replace("return Name.of(source)","return ExternalName.of(source)");
        Files.writeString(file,text);assertThat(compiler.compileProject(root).generatedFiles().toString()).contains("other.domain.ExternalName");
    }
    @Test void unrelatedInvalidFileDoesNotHideMappingErrors() throws Exception {
        model("mapping bad: Pair <- Dto { first <- source.maybe; }");
        Files.writeString(root.resolve("demo/bad.vernac"),"namespace demo; value Broken(UnknownType);");
        assertThatThrownBy(()->new VernacCompiler().compileProject(root)).hasMessageContaining("UnknownType").hasMessageContaining("optional source");
    }
    @Test void patchIntentCannotBeGuessedOrWeakened() throws Exception {
        model("mapping clear: StrictPatch <- Patch { text <- source.text; } mapping guess: Patch <- Dto { text <- source.maybe; }");
        assertThatThrownBy(()->new VernacCompiler().compileProject(root)).hasMessageContaining("clearable PATCH source").hasMessageContaining("cannot be mapped implicitly");
    }
    @Test void duplicateAssignmentsAndNoImplicitNumericConversion() throws Exception {
        model("mapping bad: Dto <- Dto { required <- source.required; required <- source.required; number <- source.required; }");
        assertThatThrownBy(()->new VernacCompiler().compileProject(root)).hasMessageContaining("Duplicate mapping assignment").hasMessageContaining("No exact conversion");
    }
}
