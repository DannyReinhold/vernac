// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.persistence;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.pipeline.VernacCompiler;
import static org.assertj.core.api.Assertions.*;

class EventSchemaTest {
    @TempDir Path directory;
    private SchemaModel schema(String definitions) throws Exception {
        Path ns=Files.createDirectories(directory.resolve("demo"));
        Files.writeString(ns.resolve("model.vernac"),"namespace demo; "+definitions);
        return new SchemaBuilder(new VernacCompiler().analyzeProject(directory),SchemaModel.empty()).build();
    }
    @Test void recordingIsExplicitAndFieldlessEventsHaveMetadataOnly() throws Exception {
        assertThat(schema("event Ping();").tables()).isEmpty();
        var model=schema("recorded event Ping();");
        assertThat(model.tables()).hasSize(1);
        var table=model.tables().getFirst();
        assertThat(table.name()).isEqualTo("@event:Ping");
        assertThat(table.primaryKey()).containsExactly("@eventId");
        assertThat(table.columns()).extracting(SchemaModel.Column::name)
                .containsExactly("@eventId","@nanoRemainder:@occurredAt","@occurredAt");
    }
    @Test void crossNamespaceSubscriberCreatesOneEventGraphAndDeliverySchema() throws Exception {
        Path other=Files.createDirectories(directory.resolve("consumer"));
        Files.writeString(other.resolve("listeners.vernac"),"""
            namespace consumer;
            import demo.Changed;
            deferred listener First on Changed behavior { execute {} }
            deferred listener Second on Changed behavior { execute {} }
            """);
        var model=schema("id ItemId; value Tag(String) list Tags; event Changed(ItemId id, Tags);");
        assertThat(model.tables()).extracting(SchemaModel.Table::name)
                .containsExactlyInAnyOrder("@event:Changed","@event:Changed.tags","@delivery");
        var child=model.tables().stream().filter(t->t.name().endsWith(".tags")).findFirst().orElseThrow();
        assertThat(child.constraints().toString()).contains("REFERENCES \"demo\".\"@event:Changed\" (\"@eventId\")");
        var delivery=model.tables().stream().filter(t->t.name().equals("@delivery")).findFirst().orElseThrow();
        assertThat(delivery.constraints().toString()).contains("UNIQUE (\"eventType\", \"eventId\", \"listenerId\")");
        assertThat(new VernacCompiler().compileProject(directory).generatedFiles().toString()).contains("JdbcChangedEvents");
    }
    @Test void eventPayloadChangesParticipateInNormalMigrationPlanning() throws Exception {
        var before=schema("value Note(String); recorded event Changed();");
        var after=schema("value Note(String); recorded event Changed(Note?);");
        String sql=new MigrationPlanner().plan(before,after,MigrationPlanner.Options.safe()).sql();
        assertThat(sql).contains("ADD COLUMN \"note\" TEXT");
        assertThatThrownBy(()->new MigrationPlanner().plan(after,before,MigrationPlanner.Options.safe()));
        assertThat(after.fingerprint()).isEqualTo(SchemaModel.parse(after.json()).fingerprint());
    }
}
