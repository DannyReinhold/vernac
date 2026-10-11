// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.persistence.*;
import org.vernac.compiler.pipeline.VernacCompiler;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Exercises actual PostgreSQL constraints, not the future generated repository runtime. */
class EntitySchemaPostgresTest {
    @TempDir Path source;

    @Test
    @EnabledIfEnvironmentVariable(named = "VERNAC_PG_TEST_URL", matches = ".+")
    void sharedReferencesNestedEntitiesAndAggregateBoundaries() throws Exception {
        Files.createDirectories(source.resolve("model"));
        Files.writeString(source.resolve("model/entities.vernac"), resource("entities.vernac"));
        var model = new SchemaBuilder(new VernacCompiler().analyzeProject(source), SchemaModel.empty()).build();
        String ddl = new MigrationPlanner().plan(SchemaModel.empty(),model,MigrationPlanner.Options.safe()).sql();
        String adminUrl = System.getenv("VERNAC_PG_TEST_URL");
        Properties credentials = new Properties();
        if (System.getenv("VERNAC_PG_TEST_USER") != null) credentials.setProperty("user",System.getenv("VERNAC_PG_TEST_USER"));
        if (System.getenv("VERNAC_PG_TEST_PASSWORD") != null) credentials.setProperty("password",System.getenv("VERNAC_PG_TEST_PASSWORD"));
        String database = "vernac_entity_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(adminUrl,credentials)) {
            boolean created = false;
            try {
                try (Statement statement = admin.createStatement()) { statement.execute("CREATE DATABASE " + database); }
                created = true;
                try (Connection db = DriverManager.getConnection(SchemaCheckMojo.databaseUrl(adminUrl,database),credentials)) {
                    db.setAutoCommit(false);
                    try (Statement statement = db.createStatement()) {
                        statement.execute(ddl);
                        statement.execute(resource("entity-relationships.sql"));
                    }
                    db.commit();
                }
            } finally {
                if (created) try (Statement statement = admin.createStatement()) { statement.execute("DROP DATABASE " + database); }
            }
        }
    }
    private static String resource(String name) throws Exception {
        try (var input = EntitySchemaPostgresTest.class.getResourceAsStream("/persistence/" + name)) {
            return new String(Objects.requireNonNull(input).readAllBytes(),StandardCharsets.UTF_8);
        }
    }
}
