// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.modules;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.persistence.*;
import static org.junit.jupiter.api.Assertions.*;
class ModulePersistenceTest {
    @TempDir Path root;
    private SchemaModel schema(String namespace) {
        return new SchemaModel(1,1,List.of(new SchemaModel.Table(namespace,"Invoice","Invoice",List.of(new SchemaModel.Column("id","UUID",false,"uuid","id")),List.of("id"),List.of())),Map.of());
    }
    private ModulePersistence.Bundle bundle(String module,String namespace) throws Exception {
        var model=schema(namespace);Path history=root.resolve(module+"/history"),sql=root.resolve(module+"/migration");
        var plan=new MigrationPlanner().plan(SchemaModel.empty(),model,MigrationPlanner.Options.safe());
        new SchemaHistory(history,sql).append("20261013090000000","initial",SchemaModel.empty(),model,plan);
        return ModulePersistence.prepare(module,model,history,sql);
    }
    @Test void bundlesReviewedHistoryDeterministicallyWithStableIndependentIdentities() throws Exception {
        var first=bundle("example.billing","example.billing");var second=bundle("example.identity","example.identity");
        ModulePersistence.verify(first.descriptor(),first.resources());ModulePersistence.verify(second.descriptor(),second.resources());
        assertEquals(first.descriptor().migrations().getFirst().file(),second.descriptor().migrations().getFirst().file());
        assertNotEquals(first.descriptor().historySchema(),second.descriptor().historySchema());
        assertNotEquals(first.descriptor().migrationLocation(),second.descriptor().migrationLocation());
        assertTrue(first.descriptor().historySchema().length()<63);
        String json=ModuleDescriptor.JSON.writeValueAsString(first.descriptor());assertFalse(json.contains(root.toString()));
        var again=ModulePersistence.prepare("example.billing",schema("example.billing"),root.resolve("example.billing/history"),root.resolve("example.billing/migration"));
        assertEquals(first.descriptor(),again.descriptor());
        first.resources().forEach((name,bytes)->assertArrayEquals(bytes,again.resources().get(name)));
        ModulePersistence.checkOwnership(List.of(first.descriptor(),second.descriptor()),SchemaModel.empty());
        assertThrows(IllegalArgumentException.class,()->ModulePersistence.checkOwnership(List.of(first.descriptor()),schema("example.billing")));
    }
    @Test void refusesSchemaDriftOrUnpairedSqlBeforePackaging() throws Exception {
        var existing=bundle("example.billing","example.billing");
        assertThrows(java.io.IOException.class,()->ModulePersistence.prepare("example.billing",schema("changed.billing"),root.resolve("example.billing/history"),root.resolve("example.billing/migration")));
        Files.writeString(root.resolve("example.billing/migration/R__unsafe.sql"),"select 1;");
        assertTrue(assertThrows(java.io.IOException.class,()->ModulePersistence.prepare("example.billing",existing.descriptor().expectedSchema(),root.resolve("example.billing/history"),root.resolve("example.billing/migration"))).getMessage().contains("Unpaired"));
    }
    @Test void detectsModifiedSqlSnapshotsAndUnexpectedPackagedFiles() throws Exception {
        var bundle=bundle("example.billing","example.billing");String prefix=ModulePersistence.directory("example.billing");
        for(String name:List.of(prefix+"migration/"+bundle.descriptor().migrations().getFirst().file(),prefix+"history/"+bundle.descriptor().migrations().getFirst().file().replace(".sql",".json"))) {
            var altered=new TreeMap<>(bundle.resources());altered.put(name,new byte[]{1});
            assertThrows(java.io.IOException.class,()->ModulePersistence.verify(bundle.descriptor(),altered));
            altered.remove(name);assertThrows(java.io.IOException.class,()->ModulePersistence.verify(bundle.descriptor(),altered));
        }
        var extra=new TreeMap<>(bundle.resources());extra.put(prefix+"migration/V20990101000000000__extra.sql",new byte[]{1});
        assertThrows(java.io.IOException.class,()->ModulePersistence.verify(bundle.descriptor(),extra));
    }
    @Test void rejectsNoncanonicalAndAmbiguousManifestData() throws Exception {
        var d=bundle("example.billing","example.billing").descriptor();
        assertThrows(IllegalArgumentException.class,()->new ModulePersistence.Descriptor(1,d.module(),"classpath:db/migration",d.historySchema(),d.historyTable(),d.migrations(),d.expectedSchema(),d.schemaFingerprint()));
        var first=d.migrations().getFirst();var sameVersion=new ModulePersistence.Migration("V20261013090000000__other.sql",first.sqlSha256(),first.snapshotSha256());
        assertThrows(IllegalArgumentException.class,()->new ModulePersistence.Descriptor(1,d.module(),d.migrationLocation(),d.historySchema(),d.historyTable(),List.of(first,sameVersion),d.expectedSchema(),d.schemaFingerprint()));
        assertThrows(IllegalArgumentException.class,()->ModulePersistence.directory("../escape"));
    }
    @Test void refusesNestedMigrationFilesAndDuplicateOwners() throws Exception {
        var bundle=bundle("example.billing","example.billing");
        assertThrows(IllegalArgumentException.class,()->ModulePersistence.checkOwnership(List.of(bundle.descriptor(),bundle.descriptor()),SchemaModel.empty()));
        Path nested=root.resolve("example.billing/migration/nested/V20261013120000000__hidden.sql");Files.createDirectories(nested.getParent());Files.writeString(nested,"select 1;");
        assertTrue(assertThrows(java.io.IOException.class,()->ModulePersistence.prepare("example.billing",bundle.descriptor().expectedSchema(),root.resolve("example.billing/history"),root.resolve("example.billing/migration"))).getMessage().contains("flat regular files"));
    }

}
