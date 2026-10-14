// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.vocabulary;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.modules.*;
import org.vernac.compiler.persistence.SchemaModel;
import org.vernac.compiler.analyzer.SemanticValidationException;
import static org.junit.jupiter.api.Assertions.*;

class VocabularyTest {
    @TempDir Path root;
    private Path source(String name,String text) throws Exception {
        Path path=root.resolve("sales/orders/"+name+".vernac");Files.createDirectories(path.getParent());Files.writeString(path,"namespace sales.orders;\n"+text);return path;
    }
    @Test void termsShareNamesWithTypesAcrossFilesButNeverBecomeTypesOrTables() throws Exception {
        source("words","term Order {description \"A submitted purchase request.\";example \"A paid order.\";note \"Not a cart.\";} term \"Outstanding balance\" {description \"Amount still owed.\";}");
        source("model","value Order(String);");
        var compiler=new VernacCompiler();var resolved=compiler.analyzeProject(root);
        assertEquals(1,resolved.project().symbols().inNamespace("sales.orders").size());
        assertEquals(2,Vocabulary.entries(resolved.project()).size());
        var generated=compiler.generate(resolved,SchemaModel.empty(),Map.of());
        assertEquals(1,generated.generatedFiles().size());
        assertTrue(generated.generatedFiles().getFirst().toString().contains("Not a cart."));
        assertEquals(SchemaModel.empty().json(),new org.vernac.compiler.persistence.SchemaBuilder(resolved,SchemaModel.empty()).build().json());
        Vocabulary.write(root.resolve("output"),resolved.project());
        String first=Files.readString(root.resolve("output/vernac-glossary.json"));
        assertTrue(first.contains("Outstanding balance"));assertFalse(first.contains(root.toString()));
        Vocabulary.write(root.resolve("output"),resolved.project());assertEquals(first,Files.readString(root.resolve("output/vernac-glossary.json")));
    }
    @Test void quotedAndUnquotedNamesAreIdenticalButNamespacesAndCaseRemainDistinct() throws Exception {
        source("a","term Order {description \"One.\";}");
        source("b","term \"Order\" {description \"Two.\";}");
        assertTrue(assertThrows(SemanticValidationException.class,()->new VernacCompiler().analyzeProject(root)).getMessage().contains("Duplicate term"));
        source("b","term order {description \"Different case.\";}");
        assertEquals(2,Vocabulary.entries(new VernacCompiler().analyzeProject(root).project()).size());
    }
    @Test void invalidTermsHaveLocatedDiagnosticsAndNoTypeFallback() throws Exception {
        for(String invalid:List.of("term Order {}","term Order {description \"\";}","term Order {description \"One\";description \"Two\";}","term \" Order\" {description \"One\";}","term \"a\\nb\" {description \"One\";}","term Order {description \"One\";note \" \";}")) {
            source("invalid",invalid);var error=assertThrows(SemanticValidationException.class,()->new VernacCompiler().analyzeProject(root));assertTrue(error.getMessage().contains("invalid.vernac:"));
        }
        source("invalid","term Missing {description \"A concept only.\";} value Bad(Missing);");
        assertThrows(SemanticValidationException.class,()->new VernacCompiler().compileProject(root));
    }
    private ModuleDescriptor publish(String prose) throws Exception {
        source("module","term Name {description "+ModuleDescriptor.JSON.writeValueAsString(prose)+";} value Name(String);");
        var compiler=new VernacCompiler();var resolved=compiler.analyzeProject(root);
        return ModuleContracts.publish(ModuleDefinition.parse(root.resolve("module.vmodule"),"module sales.api {export sales.orders.Name;}"),resolved,compiler.generate(resolved,SchemaModel.empty(),Map.of()).generatedFiles(),"example","api","1",List.of());
    }
    @Test void exportedDocumentationSurvivesMetadataRoundTripWithoutChangingStructuralCompatibility() throws Exception {
        var before=publish("Old meaning.");var after=publish("Clarified wording.");
        assertEquals(before.exports().getFirst().fingerprint(),after.exports().getFirst().fingerprint());
        assertTrue(ModuleContracts.difference(before.exports().getFirst(),after.exports().getFirst()).isEmpty());
        var decoded=ModuleDescriptor.JSON.readValue(ModuleDescriptor.JSON.writeValueAsString(after),ModuleDescriptor.class);
        assertEquals("Clarified wording.",decoded.vocabulary().getFirst().description());
        assertTrue(ModuleArtifacts.view(decoded,"sales.orders").contains("Clarified wording."));
        ModuleContracts.checkDependencies(List.of(decoded));
    }
    @Test void quotedDotsDoNotAccidentallyAssociateAcrossNamespacesAndMarkupIsPlainText() throws Exception {
        source("words","term \"api.Name\" {description \"Not a type link.\";} term Name {description \"Safe */ <script> & $ text.\";} value Name(String);");
        var compiler=new VernacCompiler();var resolved=compiler.analyzeProject(root);
        var entries=Vocabulary.entries(resolved.project());
        assertFalse(entries.stream().filter(e->e.name().equals("api.Name")).findFirst().orElseThrow().describes("sales.orders.api.Name"));
        String java=compiler.generate(resolved,SchemaModel.empty(),Map.of()).generatedFiles().getFirst().toString();
        assertTrue(java.contains("*&#47; &lt;script&gt; &amp; $ text."));
    }
    @Test void vocabularyKeywordsRemainLegalNamespaceAndMemberSegments() throws Exception {
        Path file=root.resolve("term/description/example/note/model.vernac");Files.createDirectories(file.getParent());
        Files.writeString(file,"namespace term.description.example.note; value Name(String note);term Name {description \"Meaning\";}");
        assertFalse(new VernacCompiler().compileProject(root).generatedFiles().isEmpty());
    }
}
