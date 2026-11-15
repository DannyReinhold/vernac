// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.persistence;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.modules.*;
class GlobalIdReferenceTest {
    @TempDir Path root;
    static final String MODEL="namespace demo; id AccountId; id MemberId; id OtherId; global aggregate Account[AccountId](); repository Accounts for Account {} global aggregate Member[MemberId](AccountId account references Account); repository Members for Member {}";
    private ResolvedProject project(String text) throws Exception {
        var source=root.resolve("src/main/vernac/demo/model.vernac");Files.createDirectories(source.getParent());Files.writeString(source,text);
        return new VernacCompiler().analyzeProject(root.resolve("src/main/vernac"));
    }
    @Test void nominalGlobalReferencesGenerateTrackedNoCascadeForeignKeysAndSchemaDeltas() throws Exception {
        var previous=new SchemaBuilder(project(MODEL.replace(" references Account","")),SchemaModel.empty()).build();
        var schema=new SchemaBuilder(project(MODEL),previous).build();
        var member=schema.tables().stream().filter(t->t.name().equals("Member")).findFirst().orElseThrow();
        assertTrue(member.constraints().stream().anyMatch(c->c.name().equals("@fk:reference:account") && c.clause().contains("REFERENCES \"demo\".\"Account\" (\"id\")")));
        var sql=new MigrationPlanner().plan(previous,schema,MigrationPlanner.Options.safe()).sql();
        assertTrue(sql.contains("ADD CONSTRAINT"));assertTrue(sql.contains("FOREIGN KEY"));assertFalse(sql.contains("CASCADE"));
        assertEquals(schema.json(),SchemaModel.parse(schema.json()).json());
    }
    @Test void wrongNominalTypesMutabilityMissingRepositoriesAndTenantBoundariesFailBeforeGeneration() throws Exception {
        for(String text:List.of(MODEL.replace("AccountId account","OtherId account"),MODEL.replace("AccountId account","mut AccountId account"),
            MODEL.replace("global aggregate Member","tenant aggregate Member"),MODEL.replace("global aggregate Account","tenant aggregate Account"),
            MODEL.replace("repository Accounts for Account {}",""),MODEL.replace("repository Members for Member {}",""),
            MODEL.replace("references Account","references Missing"),MODEL.replace("AccountId account","UUID account")))
            assertThrows(RuntimeException.class,()->project(text));
        assertThrows(RuntimeException.class,()->project("namespace demo; id A; value Wrong(A value references Missing);"));
    }
    @Test void moduleRoundTripPreservesExplicitReferencesAndRequiresExportedTargets() throws Exception {
        var compiler=new VernacCompiler();var resolved=project(MODEL);var generated=compiler.generate(resolved,SchemaModel.empty(),Map.of());
        var definition=ModuleDefinition.parse(root.resolve("vernac-module.vmodule"),"module demo.refs { export demo.AccountId;export demo.MemberId;export demo.Account;export demo.Member;}");
        var module=ModuleContracts.publish(definition,resolved,generated.generatedFiles(),"test","refs","1",List.of());
        var restored=ModuleDescriptor.JSON.readValue(ModuleDescriptor.JSON.writeValueAsString(module),ModuleDescriptor.class);
        var member=restored.exports().stream().filter(d->d.identity().equals("demo.Member")).findFirst().orElseThrow();
        assertEquals("demo.Account",member.fields().getFirst().referenceTarget());
        assertTrue(ModuleArtifacts.view(restored,"demo").contains("references demo.Account"));
        var consumer=root.resolve("consumer/app/model.vernac");Files.createDirectories(consumer.getParent());Files.writeString(consumer,"namespace app; import demo.MemberId; value Ref(MemberId);");
        assertDoesNotThrow(()->compiler.analyzeProject(root.resolve("consumer"),List.of(restored)));
        var missing=ModuleDefinition.parse(root.resolve("vernac-module.vmodule"),"module demo.refs {export demo.AccountId;export demo.MemberId;export demo.Member;}");
        assertThrows(RuntimeException.class,()->ModuleContracts.publish(missing,resolved,generated.generatedFiles(),"test","refs","1",List.of()));
    }
}
