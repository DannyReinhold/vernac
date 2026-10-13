// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.modules;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.persistence.SchemaModel;
import static org.junit.jupiter.api.Assertions.*;
class TypeModuleTest {
    @TempDir Path root;
    private ModuleDescriptor publish(String text,String exports,String version) throws Exception {
        Path source=root.resolve("producer/src/main/vernac/lib/values/model.vernac");Files.createDirectories(source.getParent());Files.writeString(source,text);
        var compiler=new VernacCompiler();var project=compiler.analyzeProject(source.getParent().getParent().getParent(),List.of());
        var generated=compiler.generate(project,SchemaModel.empty(),Map.of());
        return ModuleContracts.publish(ModuleDefinition.parse(root.resolve("producer/vernac-module.vmodule"),"module lib.values {"+exports+"}"),project,generated.generatedFiles(),"org.example","values",version,List.of());
    }
    @Test void exportClosureAndDefinitionGrammarAreExplicit() throws Exception {
        String source="namespace lib.values; id Id; value Name(String) list Names; value Pair(Id, Name);";
        assertThrows(SemanticValidationException.class,()->publish(source,"export lib.values.Pair;","1"));
        assertThrows(SemanticValidationException.class,()->publish(source,"export lib.values.Names;","1"));
        var descriptor=publish(source,"export lib.values.Id; export lib.values.Name; export lib.values.Pair; export lib.values.Names;","1");
        assertEquals(4,descriptor.exports().size());assertFalse(ModuleDescriptor.JSON.writeValueAsString(descriptor).contains(root.toString()));
        assertThrows(SemanticValidationException.class,()->ModuleDefinition.parse(root.resolve("x.vmodule"),"module lib.values { export lib.values.Name; export lib.values.Name; }"));
        assertThrows(SemanticValidationException.class,()->ModuleDefinition.parse(root.resolve("x.vmodule"),"namespace lib.values;"));
    }
    @Test void fingerprintsIgnorePrivateNoiseAndVersionsButDiagnoseChangedPublicFields() throws Exception {
        String export="export lib.values.Name;";
        var first=publish("namespace lib.values; value Name(String) behavior { public String display() {return self.string();} private String helper(){return \"old\";} };",export,"1.2.0");
        var second=publish("namespace lib.values; // comment\nvalue Name(String) behavior { private String renamed(){return \"new\";} public String display(){return self.string().strip();} };",export,"1.2.1");
        assertEquals(first.exports().getFirst().fingerprint(),second.exports().getFirst().fingerprint());
        var additive=publish("namespace lib.values; value Name(String) behavior { public String display(){return self.string();} public String extra(){return self.string();} };",export,"1.3");
        assertTrue(ModuleContracts.difference(first.exports().getFirst(),additive.exports().getFirst()).isEmpty());
        var changed=publish("namespace lib.values; value Name(String? string);",export,"2");
        assertTrue(ModuleContracts.difference(first.exports().getFirst(),changed.exports().getFirst()).orElseThrow().contains("string"));
        var consumer=new ModuleDescriptor(1,ModuleDescriptor.CONTRACT,"consumer.types","org.example","consumer","1",List.of(),List.of(new ModuleDescriptor.Requirement(first.module(),first.groupId(),first.artifactId(),first.version(),first.exports())));
        ModuleContracts.checkDependencies(List.of(consumer,second));
        var error=assertThrows(IllegalArgumentException.class,()->ModuleContracts.checkDependencies(List.of(consumer,changed)));
        assertTrue(error.getMessage().contains("Built against: 1.2.0"));assertTrue(error.getMessage().contains("consumer.types"));
    }
    @Test void importedValuesFlattenWithoutRegeneratingDependencyClasses() throws Exception {
        var descriptor=publish("namespace lib.values; id Id; value Name(String) list Names; value Details(Name, int count); value Private(String);","export lib.values.Id; export lib.values.Name; export lib.values.Names; export lib.values.Details;","1");
        Path source=root.resolve("consumer/src/main/vernac/app/model.vernac");Files.createDirectories(source.getParent());Files.writeString(source,"namespace app; import lib.values.Details; import lib.values.Names; id RootId; aggregate Root[RootId](Details, Names) list Roots; repository RootRepository for Root {}");
        var compiler=new VernacCompiler();var resolved=compiler.analyzeProject(source.getParent().getParent(),List.of(descriptor));
        var result=compiler.generate(resolved,SchemaModel.empty(),Map.of());
        assertTrue(result.generatedFiles().stream().noneMatch(f->f.packageName().startsWith("lib.values")));
        var schema=new org.vernac.compiler.persistence.SchemaBuilder(resolved,SchemaModel.empty(),Map.of()).build();
        assertTrue(schema.json().contains("details.count"));assertTrue(schema.json().contains("details.name"));
        Files.writeString(source,"namespace app; import lib.values.Private; value Local(Private);");
        var hidden=assertThrows(SemanticValidationException.class,()->compiler.analyzeProject(source.getParent().getParent(),List.of(descriptor)));
        assertTrue(hidden.getMessage().contains("not exported by module lib.values"));
    }
}
