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
    @Test void fallbackContractViewsPreserveSeveralCollectionsAndFunctionalSignatures() throws Exception {
        var descriptor=publish("""
            namespace lib.values;
            id Ref list Refs, set UniqueRefs;
            value Name(String) list Names, set UniqueNames;
            value Status = OPEN | CLOSED;
            port Lookup {Name? byRef(Ref);}
            service Formatting behavior {public Name format(Name name){return name;}}
            usecase Make(Name) returns (Name, Status? status) behavior {execute {return Result.of(name);}}
            event Changed(Ref, Name);
            ""","export lib.values.Ref;export lib.values.Refs;export lib.values.UniqueRefs;export lib.values.Name;export lib.values.Names;export lib.values.UniqueNames;export lib.values.Status;export lib.values.Lookup;export lib.values.Formatting;export lib.values.Make;export lib.values.Changed;","1");
        String view=ModuleArtifacts.view(descriptor,"lib.values");
        assertTrue(view.contains("list Names, set UniqueNames"));assertTrue(view.contains("list Refs, set UniqueRefs"));
        assertTrue(view.contains("service Formatting behavior"));
        assertTrue(view.contains("returns (lib.values.Name name, lib.values.Status? status)"));
        var lexer=new org.vernac.compiler.parser.VernacLexer(org.antlr.v4.runtime.CharStreams.fromString(view));
        var parser=new org.vernac.compiler.parser.VernacParser(new org.antlr.v4.runtime.CommonTokenStream(lexer));
        parser.compilationUnit();assertEquals(0,parser.getNumberOfSyntaxErrors(),view);
        String overview=ModuleArtifacts.manifestView(descriptor);
        assertTrue(overview.contains("module lib.values {"));assertTrue(overview.contains("org.example:values:1"));
        assertTrue(descriptor.activatable());
        assertDoesNotThrow(()->ModuleDefinition.parse(root.resolve("view.vmodule"),overview));
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
    @Test void functionalContractsIgnorePrivateWiringAndRequireInlineResultExports() throws Exception {
        String exports="export lib.values.Name; export lib.values.Make;";
        String first="namespace lib.values; value Name(String); service Helper behavior {public Name make(){return Name.of(\"one\");}} usecase Make() returns (Name name) uses Helper helper behavior {execute {throw new UnsupportedOperationException();}}";
        String second=first.replace("Helper","DifferentHelper").replace("one","two");
        var before=publish(first,exports,"1");var after=publish(second,exports,"2");
        assertEquals(before.exports().stream().filter(d->d.kind().equals("USE_CASE")).findFirst().orElseThrow().fingerprint(),after.exports().stream().filter(d->d.kind().equals("USE_CASE")).findFirst().orElseThrow().fingerprint());
        assertTrue(after.internalTypes().contains("lib.values.DifferentHelper"));
        assertEquals(List.of("lib.values.DifferentHelper"),after.components().stream().filter(c->c.kind().equals("USE_CASE")).findFirst().orElseThrow().dependencies());
        assertThrows(SemanticValidationException.class,()->publish(first,"export lib.values.Make;","1"));
        ModuleContracts.checkDependencies(List.of(after));
        assertTrue(ModuleArtifacts.view(after,"lib.values").contains("returns (lib.values.Name name)"));
        Path consumer=root.resolve("consumer/src/main/vernac/app/model.vernac");Files.createDirectories(consumer.getParent());
        Files.writeString(consumer,"namespace app; import lib.values.DifferentHelper; service Invalid uses DifferentHelper helper behavior {public void run(){}}");
        var hidden=assertThrows(SemanticValidationException.class,()->new VernacCompiler().analyzeProject(root.resolve("consumer/src/main/vernac"),List.of(after)));
        assertTrue(hidden.getMessage().contains("not exported"));
    }

}
