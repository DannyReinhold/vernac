// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.vernac.runtime.persistence.UniqueConstraintViolationException;

import java.net.URLClassLoader;

class UniqueModulePostgresTest extends ModuleMigrationFixture {
    @Test
    @EnabledIfEnvironmentVariable(named = "VERNAC_PG_TEST_URL", matches = ".+")
    void packagedModuleReplaysFormatFourAndRejectsConflictAndCollationDriftOnRestart()
            throws Exception {
        unique = true;
        var published = fixtures();
        assertEquals(4, published.getFirst().schema().formatVersion());
        database(
                migrations -> {
                    var runtime = provisionRuntime(migrations);
                    try (var code =
                            new URLClassLoader(
                                    new java.net.URL[] {resources.toUri().toURL()},
                                    getClass().getClassLoader())) {
                        for (int restart = 0; restart < 2; restart++) {
                            int attempt = restart;
                            boot(migrations, runtime, code)
                                    .run(
                                            context -> {
                                                assertNull(
                                                        context.getStartupFailure(),
                                                        () ->
                                                                String.valueOf(
                                                                        context
                                                                                .getStartupFailure()));
                                                var name =
                                                        code.loadClass("db.required.domain.Name");
                                                var submit = context.getBean("db.dependent.Submit");
                                                Object value =
                                                        name.getMethod("of", String.class)
                                                                .invoke(null, "private-module-key");
                                                if (attempt == 0)
                                                    submit.getClass()
                                                            .getMethod("execute", name)
                                                            .invoke(submit, value);
                                                else {
                                                    var error =
                                                            assertThrows(
                                                                    java.lang.reflect
                                                                            .InvocationTargetException
                                                                            .class,
                                                                    () ->
                                                                            submit.getClass()
                                                                                    .getMethod(
                                                                                            "execute",
                                                                                            name)
                                                                                    .invoke(
                                                                                            submit,
                                                                                            value));
                                                    Throwable cause = error.getCause();
                                                    while (cause.getCause() != null)
                                                        cause = cause.getCause();
                                                    var conflict =
                                                            assertInstanceOf(
                                                                    UniqueConstraintViolationException
                                                                            .class,
                                                                    cause);
                                                    assertEquals(
                                                            "db.required.Root",
                                                            conflict.aggregate());
                                                    assertEquals("name", conflict.field());
                                                    assertFalse(
                                                            conflict.toString()
                                                                    .contains(
                                                                            "private-module-key"));
                                                }
                                            });
                        }
                        assertEquals(
                                1,
                                count(migrations, "SELECT count(*) FROM \"db.required\".\"Root\""));
                        assertEquals(
                                1,
                                count(
                                        migrations,
                                        "SELECT count(*) FROM \"db.dependent\".\"Receipt\""));
                        try (var c = migrations.getConnection();
                                var sql = c.createStatement()) {
                            sql.execute(
                                    "ALTER TABLE \"db.required\".\"Root\" ALTER COLUMN \"name\""
                                        + " TYPE TEXT COLLATE \"pg_catalog\".\"default\"");
                        }
                        boot(migrations, runtime, code)
                                .run(
                                        context -> {
                                            var failure = context.getStartupFailure();
                                            assertNotNull(failure);
                                            boolean collation = false;
                                            for (Throwable cause = failure;
                                                    cause != null;
                                                    cause = cause.getCause())
                                                collation |=
                                                        String.valueOf(cause.getMessage())
                                                                .contains("collation");
                                            assertTrue(collation, String.valueOf(failure));
                                        });
                    } finally {
                        try (var c = migrations.getConnection();
                                var sql = c.createStatement()) {
                            sql.execute("DROP OWNED BY " + runtime.role());
                            sql.execute("DROP ROLE " + runtime.role());
                        }
                    }
                });
    }
}
