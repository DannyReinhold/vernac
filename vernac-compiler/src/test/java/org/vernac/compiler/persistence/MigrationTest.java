// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.vernac.compiler.persistence.SchemaModel.*;

class MigrationTest {
    @TempDir Path root;
    private SchemaModel model(Column... extra) {
        List<Column> columns = new ArrayList<>(List.of(new Column("id", "UUID", false, "UUID", "model.Tour")));
        columns.addAll(List.of(extra));
        return new SchemaModel(1,1,List.of(new Table("model", "Tour", "model.Tour",columns,List.of("id"),List.of())),Map.of());
    }
    @Test void initialAndIncrementalCandidatesIncludeResponsibilityAndDoNotRecreateTables() {
        var planner = new MigrationPlanner(); var initial = model();
        var first = planner.plan(SchemaModel.empty(), initial, MigrationPlanner.Options.safe());
        assertTrue(first.sql().contains("solely responsible"));
        assertTrue(first.sql().contains("CREATE TABLE \"model\".\"Tour\""));
        var next = model(new Column("note", "TEXT", true, "String:", "note"));
        var delta = planner.plan(initial, next, MigrationPlanner.Options.safe());
        assertTrue(delta.sql().contains("ADD COLUMN \"note\" TEXT"));
        assertFalse(delta.sql().contains("CREATE TABLE"));
        assertFalse(planner.plan(next,next,MigrationPlanner.Options.safe()).changed());
    }
    @Test void destructiveOperationsRequireForceAndForceDoesNotInventBackfillsOrConversions() {
        var planner = new MigrationPlanner();
        var before = model(new Column("note", "TEXT", true, "String:", "note"));
        assertThrows(IllegalArgumentException.class, () -> planner.plan(before, model(), MigrationPlanner.Options.safe()));
        var force = new MigrationPlanner.Options(true, Map.of(), Map.of());
        assertTrue(planner.plan(before, model(), force).sql().contains("force bypasses"));
        var required = model(new Column("note", "TEXT", false, "String:", "note"));
        assertThrows(IllegalArgumentException.class, () -> planner.plan(model(),required,force));
        var done = planner.plan(model(),required,new MigrationPlanner.Options(false,Map.of("model/Tour/note", "'reviewed'"), Map.of()));
        assertTrue(done.sql().indexOf("UPDATE") < done.sql().indexOf("SET NOT NULL"));
        var integer = model(new Column("note", "INTEGER", true, "Integer:", "note"));
        assertThrows(IllegalArgumentException.class, () -> planner.plan(before,integer,force));
        assertTrue(planner.plan(before,integer,new MigrationPlanner.Options(true,Map.of(),Map.of("model/Tour/note", "\"note\"::integer"))).sql().contains("USING"));
    }
    @Test void knownNumericWideningNeedsNeitherForceNorAnInventedConversion() {
        var before = model(new Column("count", "INTEGER", false, "int:", "count"));
        var next = model(new Column("count", "BIGINT", false, "long:", "count"));
        var plan = new MigrationPlanner().plan(before,next,MigrationPlanner.Options.safe());
        assertTrue(plan.sql().contains("ALTER COLUMN \"count\" TYPE BIGINT"));
        assertTrue(plan.risks().isEmpty());
    }
    @Test void addingAnOptionalColumnKeepsExistingChecksAndForeignKeys() {
        var before = withConstraints(model(), new Constraint("@check:stable", "CHECK (\"id\" IS NOT NULL)"));
        var after = withConstraints(model(new Column("note","TEXT",true,"String:","note")), before.tables().getFirst().constraints().toArray(Constraint[]::new));
        String sql = new MigrationPlanner().plan(before,after,MigrationPlanner.Options.safe()).sql();
        assertTrue(sql.contains("ADD COLUMN")); assertFalse(sql.contains("DROP CONSTRAINT")); assertFalse(sql.contains("ADD CONSTRAINT"));
    }
    @Test void enumExpansionOnlyReplacesTheEnumCheck() {
        var base = model(new Column("status","TEXT",false,"String:","status"));
        var stable = new Constraint("@check:stable", "CHECK (\"id\" IS NOT NULL)");
        var before = withConstraints(base, stable, new Constraint("@check:enum", "CHECK (\"status\" IN ('NEW'))"));
        var after = withConstraints(base, stable, new Constraint("@check:enum", "CHECK (\"status\" IN ('NEW', 'DONE'))"));
        String sql = new MigrationPlanner().plan(before,after,MigrationPlanner.Options.safe()).sql();
        assertFalse(sql.contains("@check:stable"));
        assertTrue(sql.indexOf("DROP CONSTRAINT") < sql.indexOf("ADD CONSTRAINT"));
        assertTrue(sql.contains("'DONE'"));
    }
    @Test void typeChangesRebuildOnlyDependentChecksIncludingUnchangedClauses() {
        var check = new Constraint("@check:positive", "CHECK (\"count\" >= 0)");
        var unrelated = new Constraint("@check:identity", "CHECK (\"id\" IS NOT NULL)");
        var before = withConstraints(model(new Column("count","INTEGER",false,"int:","count")),check,unrelated);
        var after = withConstraints(model(new Column("count","BIGINT",false,"long:","count")),check,unrelated);
        String sql = new MigrationPlanner().plan(before,after,MigrationPlanner.Options.safe()).sql();
        assertFalse(sql.contains("@check:identity"));
        assertTrue(sql.indexOf("DROP CONSTRAINT") < sql.indexOf("ALTER COLUMN"));
        assertTrue(sql.indexOf("ALTER COLUMN") < sql.indexOf("ADD CONSTRAINT"));
    }
    @Test void removingAnEntityGraphDropsAllForeignKeysBeforeTablesWithoutCascade() {
        Table root = model().tables().getFirst();
        Table entity = new Table("model","Tour.@entity:model.Place","model.Place",
                List.of(new Column("id","UUID",false,"UUID","id"),new Column("@aggregateId","UUID",false,"UUID","root")),
                List.of("@aggregateId","id"),List.of(new Constraint("@fk:root","FOREIGN KEY (\"@aggregateId\") REFERENCES \"model\".\"Tour\" (\"id\")")));
        Table links = new Table("model","Tour.places","places",entity.columns(),entity.primaryKey(),
                List.of(new Constraint("@fk:entity","FOREIGN KEY (\"@aggregateId\", \"id\") REFERENCES \"model\".\"Tour.@entity:model.Place\" (\"@aggregateId\", \"id\")")));
        var before = new SchemaModel(1,1,List.of(root,entity,links),Map.of());
        assertThrows(IllegalArgumentException.class, () -> new MigrationPlanner().plan(before,model(),MigrationPlanner.Options.safe()));
        String sql = new MigrationPlanner().plan(before,model(),new MigrationPlanner.Options(true,Map.of(),Map.of())).sql();
        assertTrue(sql.lastIndexOf("DROP CONSTRAINT") < sql.indexOf("\nDROP TABLE \"model"));
        assertFalse(sql.contains("CASCADE"));
        assertFalse(sql.contains("DROP TABLE \"model\".\"Tour\";"));
    }
    private static SchemaModel withConstraints(SchemaModel model, Constraint... constraints) {
        var t = model.tables().getFirst();
        return new SchemaModel(1,1,List.of(new Table(t.namespace(),t.name(),t.origin(),t.columns(),t.primaryKey(),List.of(constraints))),model.enumCodes());
    }
    @Test void historyRejectsBranchesAndNeverOverwritesAnExistingCandidate() throws Exception {
        var h = new SchemaHistory(root.resolve("history"),root.resolve("sql"));
        var a = model(); var planner = new MigrationPlanner();
        h.append("20261009120000000","initial",SchemaModel.empty(),a,planner.plan(SchemaModel.empty(),a,MigrationPlanner.Options.safe()));
        assertEquals(a,h.read());
        assertThrows(Exception.class, () -> h.append("20261009120000000","initial",SchemaModel.empty(),a,planner.plan(SchemaModel.empty(),a,MigrationPlanner.Options.safe())));
        var branched = new SchemaHistory.Snapshot(1,"V20261009130000000__branch.sql",SchemaModel.empty().fingerprint(),a.fingerprint(),a);
        Files.writeString(root.resolve("history/V20261009130000000__branch.json"),SchemaModel.JSON.writeValueAsString(branched));
        Files.writeString(root.resolve("sql/V20261009130000000__branch.sql"),"-- candidate");
        assertTrue(assertThrows(Exception.class,h::read).getMessage().contains("diverges"));
    }
}
