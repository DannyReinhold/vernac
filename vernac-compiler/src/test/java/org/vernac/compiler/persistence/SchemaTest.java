// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.pipeline.VernacCompiler;
import org.vernac.compiler.symbols.BuiltinTypes;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SchemaTest {
    @TempDir Path root;
    private SchemaModel build(String text) throws Exception {
        Files.createDirectories(root.resolve("model"));
        Files.writeString(root.resolve("model/test.vernac"), "namespace model;\n" + text);
        return new SchemaBuilder(new VernacCompiler().analyzeProject(root), SchemaModel.empty()).build();
    }
    @Test void namesPreserveUnicodeCaseKeywordsAndShortenByUtf8Bytes() {
        assertEquals("\"order\"", SqlNames.name("order"));
        assertEquals("\"org.example\".\"Tour\"", SqlNames.table("org.example", "Tour"));
        assertNotEquals(SqlNames.name("URL"), SqlNames.name("Url"));
        assertEquals("Straße", SqlNames.physical("Straße"));
        String longName = "𐐀".repeat(40);
        String result = SqlNames.physical(longName);
        assertTrue(result.getBytes(StandardCharsets.UTF_8).length <= 63);
        assertFalse(result.contains("�"));
        assertEquals(result, SqlNames.physical(longName));
        assertNotEquals(result, SqlNames.physical(longName + "a"));
        assertEquals("\"a\"\"b\"", SqlNames.quote("a\"b"));
    }
    @Test void everyApprovedBuiltinHasAnExplicitLayout() {
        for (String type : BuiltinTypes.names()) assertFalse(ScalarMappings.of(type).isEmpty(), type);
        assertThrows(IllegalArgumentException.class, () -> ScalarMappings.of("Object"));
    }
    @Test void noRepositoryMeansNoTables() throws Exception {
        assertTrue(build("id TourId; value Title(String); aggregate Tour[TourId](Title);").tables().isEmpty());
    }
    @Test void flattenedValuesAndTechnicalComponentsAndPresenceAreDeterministic() throws Exception {
        var model = build("""
            id TourId;
            value Title(String);
            value Money(BigDecimal amount, Currency currency);
            value Details(String? description, String? note);
            value Wrapped(Details);
            value Required(String code, String? note);
            aggregate Tour[TourId](Title order, Money price, Details? details, Wrapped? wrapped, Required? required);
            repository TourRepository for Tour { }
            """);
        var table = model.tables().getFirst();
        var columns = table.columns().stream().map(SchemaModel.Column::name).toList();
        assertTrue(columns.containsAll(List.of("order", "price.amount", "price.currency", "@scale:price.amount", "@present:details:model.Details", "@present:wrapped:model.Wrapped")));
        assertTrue(columns.stream().noneMatch(n -> n.startsWith("@present:required")));
        assertEquals("TEXT", table.columns().stream().filter(c -> c.name().equals("order")).findFirst().orElseThrow().sqlType());
        assertEquals(model, SchemaModel.parse(model.json()));
        assertFalse(model.json().contains(root.toString()));
        assertEquals(model.fingerprint(), SchemaModel.parse(model.json()).fingerprint());
    }
    @Test void nestedOptionalSingleWrappersReuseExistingPresenceWitnesses() throws Exception {
        var model = build("""
            id TourId; value Inner(String? text); value Outer(Inner? inner);
            aggregate Tour[TourId](Outer? item); repository TourRepository for Tour { }
            """);
        var names = model.tables().getFirst().columns().stream().map(SchemaModel.Column::name).toList();
        assertFalse(names.contains("@present:item:model.Outer"));
        assertTrue(names.contains("@present:item:model.Inner"));
    }
    @Test void collectionsHaveListPositionsButSetsDoNot() throws Exception {
        var model = build("""
            id TourId; value Title(String) list Titles; value Tag(String) set Tags;
            aggregate Tour[TourId](Titles, Tags?); repository TourRepository for Tour { }
            """);
        var list = model.tables().stream().filter(t -> t.name().equals("Tour.titles")).findFirst().orElseThrow();
        var set = model.tables().stream().filter(t -> t.name().equals("Tour.tags")).findFirst().orElseThrow();
        assertEquals(List.of("@ownerId", "@position"), list.primaryKey());
        assertTrue(set.columns().stream().noneMatch(c -> c.name().equals("@position")));
        assertTrue(model.tables().stream().filter(t -> t.name().equals("Tour")).findFirst().orElseThrow().columns().stream().anyMatch(c -> c.name().equals("@present:tags")));
    }
    @Test void sharedEntitiesAndSubentitiesHaveOneStateTableAndSeparateRelations() throws Exception {
        var model = build("""
            id TourId; id StopId; id ParcelId; value Title(String);
            value Address(String street, String city); value Tag(String) list Tags;
            entity Parcel[ParcelId](Title) set Parcels;
            entity Stop[StopId](Address, mut Parcels, Tags) list Stops;
            aggregate Tour[TourId](Stops allStops, Stops niceStops, Stop? preferred);
            repository TourRepository for Tour { }
            """);
        var stop = table(model, "Tour.@entity:model.Stop");
        var parcel = table(model, "Tour.@entity:model.Parcel");
        assertEquals(List.of("@aggregateId", "id"), stop.primaryKey());
        assertEquals(1, model.tables().stream().filter(t -> t.name().equals(stop.name())).count());
        assertEquals(1, model.tables().stream().filter(t -> t.name().equals(parcel.name())).count());
        assertTrue(stop.columns().stream().anyMatch(c -> c.name().equals("address.street")));
        assertEquals(List.of("@aggregateId", "@position"), table(model,"Tour.allStops").primaryKey());
        assertEquals(List.of("@aggregateId", "@position"), table(model,"Tour.niceStops").primaryKey());
        var children = table(model, "Tour.@entity:model.Stop.parcels");
        assertEquals(List.of("@aggregateId", "@ownerId", "@entityId"), children.primaryKey());
        assertTrue(children.columns().stream().noneMatch(c -> c.name().equals("@position")));
        assertTrue(children.constraints().stream().anyMatch(c -> c.clause().contains("REFERENCES " + stop.sqlName())));
        assertTrue(children.constraints().stream().anyMatch(c -> c.clause().contains("REFERENCES " + parcel.sqlName())));
        var preferred = table(model,"Tour").columns().stream().filter(c -> c.name().equals("preferred")).findFirst().orElseThrow();
        assertTrue(preferred.nullable());
        assertEquals(List.of("@aggregateId", "@ownerId", "@position"), table(model,"Tour.@entity:model.Stop.tags").primaryKey());
        assertTrue(table(model,"Tour.@entity:model.Stop.tags").constraints().stream()
                .anyMatch(c -> c.clause().contains("FOREIGN KEY (\"@aggregateId\", \"@ownerId\")")));
    }
    @Test void directSubentitiesAndOptionalEntityListsUseCompositeReferences() throws Exception {
        var model = build("""
            id RootId; id ParentId; id ChildId; value Label(String);
            entity Child[ChildId](Label) list Children;
            entity Parent[ParentId](Child child, Child? other, Children? children);
            aggregate Root[RootId](Parent); repository Roots for Root { }
            """);
        var parent = table(model,"Root.@entity:model.Parent");
        assertFalse(parent.columns().stream().filter(c -> c.name().equals("child")).findFirst().orElseThrow().nullable());
        assertTrue(parent.columns().stream().anyMatch(c -> c.name().equals("@present:children")));
        assertEquals(List.of("@aggregateId", "@ownerId", "@position"), table(model,"Root.@entity:model.Parent.children").primaryKey());
        assertTrue(parent.constraints().stream().filter(c -> c.name().startsWith("@fk:entity:"))
                .allMatch(c -> c.clause().contains("\"@aggregateId\", ")));
    }
    @Test void equalEntityNamesAcrossNamespacesAndRootsDoNotShareStorage() throws Exception {
        for (String ns : List.of("first", "second")) {
            Files.createDirectories(root.resolve(ns));
            Files.writeString(root.resolve(ns + "/place.vernac"), "namespace " + ns + "; id PlaceId; value Label(String); entity Place[PlaceId](Label) list Places;");
        }
        var model = build("""
            id RootId;
            aggregate Root[RootId](first.Places firstPlaces, second.Places secondPlaces);
            aggregate Other[RootId](first.Places);
            repository Roots for Root { } repository Others for Other { }
            """);
        table(model,"Root.@entity:first.Place"); table(model,"Root.@entity:second.Place"); table(model,"Other.@entity:first.Place");
        assertEquals(model,SchemaModel.parse(model.json()));
    }
    @Test void structuralChecksOmitRedundantNotNullButKeepOptionalShapesAndAbsence() throws Exception {
        var model = build("""
            id RootId; value Title(String); value Amount(BigDecimal);
            value Notes(String? first, String? second);
            aggregate Root[RootId](Title, Amount required, Amount? optional, Notes? notes);
            repository Roots for Root { }
            """);
        var constraints = table(model,"Root").constraints();
        assertFalse(constraints.stream().anyMatch(c -> c.name().equals("@check:shape:title")));
        assertFalse(constraints.stream().anyMatch(c -> c.name().equals("@check:shape:required")));
        assertFalse(constraints.stream().anyMatch(c -> c.name().startsWith("@check:presence:notes")));
        assertFalse(constraints.stream().anyMatch(c -> c.name().startsWith("@check:shape:notes.")));
        assertTrue(constraints.stream().anyMatch(c -> c.name().equals("@check:shape:optional")));
        assertTrue(constraints.stream().anyMatch(c -> c.name().equals("@check:absent:notes:model.Notes")));
    }
    @Test void containmentCyclesAreStillRejected() {
        assertThrows(Exception.class, () -> build("""
            id AId; id BId; id RootId;
            entity A[AId](B?) list As; entity B[BId](As);
            aggregate Root[RootId](A); repository Roots for Root { }
            """));
    }
    private static SchemaModel.Table table(SchemaModel model, String name) {
        return model.tables().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }
    @Test void enumsHaveExplicitSnapshotCodes() throws Exception {
        var model = build("id TourId; value Status = NEW | DONE; aggregate Tour[TourId](Status); repository TourRepository for Tour { }");
        assertEquals(Map.of("NEW", "NEW", "DONE", "DONE"), model.enumCodes().get("model.Status"));
        var sql = new MigrationPlanner().plan(SchemaModel.empty(), model, MigrationPlanner.Options.safe()).sql();
        assertTrue(sql.contains("'NEW'")); assertFalse(sql.contains("CREATE TYPE"));
    }
    @Test void equalAggregateNamesInDifferentNamespacesRemainDistinct() throws Exception {
        for (String ns : List.of("first", "second")) {
            Files.createDirectories(root.resolve(ns));
            Files.writeString(root.resolve(ns + "/a.vernac"), "namespace " + ns + "; id TourId; value Title(String); aggregate Tour[TourId](Title); repository TourRepository for Tour { }");
        }
        var model = new SchemaBuilder(new VernacCompiler().analyzeProject(root),SchemaModel.empty()).build();
        assertEquals(List.of("first/Tour", "second/Tour"), model.tables().stream().map(SchemaModel.Table::identity).toList());
    }
    @Test void enumRenameCanExplicitlyRetainItsStoredCode() throws Exception {
        String source = "id TourId; value Status = NEW | DONE; aggregate Tour[TourId](Status); repository TourRepository for Tour { }";
        var previous = build(source);
        build(source.replace("DONE", "FINISHED"));
        var resolved = new VernacCompiler().analyzeProject(root);
        var next = new SchemaBuilder(resolved,previous,Map.of("model.Status",Map.of("NEW","NEW","FINISHED","DONE"))).build();
        assertEquals("DONE",next.enumCodes().get("model.Status").get("FINISHED"));
        assertTrue(new MigrationPlanner().plan(previous,next,MigrationPlanner.Options.safe()).risks().isEmpty());
        assertThrows(Exception.class, () -> new SchemaBuilder(resolved,previous,Map.of("model.Status",Map.of("NEW","NEW"))).build());
    }

}
