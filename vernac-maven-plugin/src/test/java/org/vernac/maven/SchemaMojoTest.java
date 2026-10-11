// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.vernac.compiler.persistence.*;
import java.nio.file.*;
import java.lang.reflect.Field;
import static org.junit.jupiter.api.Assertions.*;

class SchemaMojoTest {
    @TempDir Path root;
    private void source(boolean optional) throws Exception {
        Files.createDirectories(root.resolve("src/model"));
        Files.writeString(root.resolve("src/model/model.vernac"), "namespace model; id TourId; value Title(String); value Notes(String? a, String? b); value Tag(String) list Tags; aggregate Tour[TourId](Notes? notes, Tags tags, Title title"
                + (optional ? ", Title? note" : "") + "); repository TourRepository for Tour { }");
    }
    private <T extends SchemaMojo> T configure(T mojo) {
        mojo.sourceDirectory = root.resolve("src").toFile();
        mojo.historyDirectory = root.resolve("history").toFile();
        mojo.migrationDirectory = root.resolve("migration").toFile();
        mojo.schemaOutput = root.resolve("target").toFile();
        return mojo;
    }
    private void migration(String version) throws Exception {
        var mojo = configure(new MigrationMojo());
        set(mojo,"description","test"); set(mojo,"version",version); mojo.execute();
    }
    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field); f.setAccessible(true); f.set(target,value);
    }
    @Test void schemaGoalDoesNotChangeHistoryAndMigrationGoalWritesInitialAndNextCandidates() throws Exception {
        source(false);
        configure(new SchemaMojo()).execute();
        assertTrue(Files.readString(root.resolve("target/schema.sql")).contains("CREATE TABLE"));
        assertFalse(Files.exists(root.resolve("history")));
        migration("20261009120000000");
        String initial = Files.readString(root.resolve("migration/V20261009120000000__test.sql"));
        source(true); migration("20261009130000000");
        assertEquals(initial,Files.readString(root.resolve("migration/V20261009120000000__test.sql")));
        assertTrue(Files.readString(root.resolve("migration/V20261009130000000__test.sql")).contains("ADD COLUMN \"note\" TEXT"));
        assertEquals(2, new SchemaHistory(root.resolve("history"),root.resolve("migration")).read().tables().getFirst().columns().stream().filter(c -> c.name().equals("title") || c.name().equals("note")).count());
        // Identical model produces no third candidate even with a different version.
        migration("20261009140000000");
        assertFalse(Files.exists(root.resolve("migration/V20261009140000000__test.sql")));
    }
    @Test void databaseUrlOnlyChangesDatabaseComponent() {
        assertEquals("jdbc:postgresql://localhost:5432/test123?sslmode=require",SchemaCheckMojo.databaseUrl("jdbc:postgresql://localhost:5432/postgres?sslmode=require","test123"));
        assertThrows(IllegalArgumentException.class, () -> SchemaCheckMojo.databaseUrl("jdbc:h2:mem:x","test"));
    }
    @Test
    @EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL", matches=".+")
    void postgresReplaysInitialAndNextMigrationAndDetectsOwnedDrift() throws Exception {
        source(false); migration("20261009120000000");
        source(true); migration("20261009130000000");
        var check = configure(new SchemaCheckMojo());
        set(check,"adminUrl",System.getenv("VERNAC_PG_TEST_URL"));
        set(check,"user",System.getenv("VERNAC_PG_TEST_USER"));
        set(check,"password",System.getenv("VERNAC_PG_TEST_PASSWORD"));
        // Foreign tables are ignored, but an extra column in a managed table is not.
        Files.writeString(root.resolve("migration/V20261009140000000__foreign.sql"),"CREATE TABLE public.user_owned (id integer);");
        check.execute();
        Files.writeString(root.resolve("migration/V20261009150000000__drift.sql"),"ALTER TABLE \"model\".\"Tour\" ADD COLUMN stray text;");
        assertTrue(assertThrows(Exception.class,check::execute).getMessage().contains("mismatch"));
    }
}
