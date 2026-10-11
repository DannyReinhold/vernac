// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.flywaydb.core.Flyway;
import org.vernac.compiler.persistence.*;
import java.sql.*;
import java.util.*;
import java.nio.file.Files;

/** Replays migrations and compares PostgreSQL catalogs in two newly created disposable databases. */
@Mojo(name="schema-check", threadSafe=false)
public final class SchemaCheckMojo extends SchemaMojo {
    /** Administrative connection to a disposable test server; requires CREATEDB, never uses an application database. */
    @Parameter(property="vernac.checkUrl", defaultValue="${env.VERNAC_CHECK_URL}", required=true)
    private String adminUrl;
    @Parameter(property="vernac.checkUser", defaultValue="${env.VERNAC_CHECK_USER}")
    private String user;
    @Parameter(defaultValue="${env.VERNAC_CHECK_PASSWORD}")
    private String password;
    @Override public void execute() throws MojoExecutionException, MojoFailureException {
        String actual = "vernac_check_" + UUID.randomUUID().toString().replace("-", "");
        String expected = "vernac_check_" + UUID.randomUUID().toString().replace("-", "");
        List<String> created = new ArrayList<>();
        try {
            databaseUrl(adminUrl, actual);
            SchemaModel snapshot = history().read(), target = model(snapshot);
            if (!target.equals(snapshot)) throw new IllegalArgumentException("Current model differs from migration history. Generate and review the next candidate.");
            // Source history keeps ownership even after a table has been removed.
            Set<String> owned = new TreeSet<>();
            if (historyDirectory.isDirectory()) try (var files = Files.list(historyDirectory.toPath())) {
                for (var file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                    var s = SchemaModel.JSON.readValue(Files.readString(file), SchemaHistory.Snapshot.class);
                    s.model().tables().forEach(t -> owned.add(SqlNames.physical(t.namespace()) + "/" + SqlNames.physical(t.name())));
                }
            }
            try (Connection admin = connect(adminUrl)) {
                try {
                    for (String db : List.of(actual, expected)) {
                        try (Statement s = admin.createStatement()) { s.execute("CREATE DATABASE " + SqlNames.quote(db)); }
                        created.add(db);
                    }
                    Flyway.configure().dataSource(databaseUrl(adminUrl, actual), user, password)
                            .locations("filesystem:" + migrationDirectory.getAbsolutePath())
                            .validateMigrationNaming(true).load().migrate();
                    try (Connection c = connect(databaseUrl(adminUrl, expected)); Statement s = c.createStatement()) {
                        s.execute(new MigrationPlanner().plan(SchemaModel.empty(), target, MigrationPlanner.Options.safe()).sql());
                    }
                    try (Connection a = connect(databaseUrl(adminUrl, actual)); Connection e = connect(databaseUrl(adminUrl, expected))) {
                        var observed = catalog(a, owned); var wanted = catalog(e, owned);
                        if (!observed.equals(wanted)) {
                            Set<String> missing = new TreeSet<>(wanted); missing.removeAll(observed);
                            Set<String> extra = new TreeSet<>(observed); extra.removeAll(wanted);
                            throw new IllegalArgumentException("Migration/schema mismatch. Missing: " + missing + "; unexpected: " + extra);
                        }
                    }
                } finally {
                    SQLException cleanupFailure = null;
                    for (String db : created) try (Statement s = admin.createStatement()) {
                        s.execute("DROP DATABASE " + SqlNames.quote(db) + " WITH (FORCE)");
                    } catch (SQLException e) {
                        if (cleanupFailure == null) cleanupFailure = e; else cleanupFailure.addSuppressed(e);
                    }
                    if (cleanupFailure != null) throw cleanupFailure;
                }
            }
            getLog().info("Migration replay matches the expected owned schema. Foreign application tables were ignored.");
            getLog().warn("This test used empty disposable databases. It is no guarantee for production data, locking, performance or deployment compatibility; review and approval remain your responsibility.");
        } catch (IllegalArgumentException e) { throw new MojoFailureException(e.getMessage()); }
        catch (Exception e) {
            // Drivers may include credentials or complete URLs in their messages.
            throw new MojoFailureException("Disposable PostgreSQL verification failed (" + e.getClass().getSimpleName()
                    + "). Check the test server, credentials, CREATEDB permission and migration SQL. No connection details are logged.");
        }
    }
    private Connection connect(String url) throws SQLException { return DriverManager.getConnection(url, user, password); }
    static String databaseUrl(String url, String database) {
        if (url == null || !url.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException("Use a jdbc:postgresql://host:port/database test-server URL.");
        if (url.matches("(?i).*[?&](user|password)=.*"))
            throw new IllegalArgumentException("Pass credentials through the dedicated environment/settings parameters, not in the JDBC URL.");
        int query = url.indexOf('?');
        String base = query < 0 ? url : url.substring(0, query);
        int slash = base.lastIndexOf('/');
        if (slash <= "jdbc:postgresql://".length()) throw new IllegalArgumentException("Test-server URL must include a database name.");
        return base.substring(0, slash + 1) + database + (query < 0 ? "" : url.substring(query));
    }
    static SortedSet<String> catalog(Connection c, Set<String> owned) throws SQLException {
        SortedSet<String> result = new TreeSet<>();
        String prefix = "SELECT n.nspname, t.relname, ";
        String from = " FROM pg_class t JOIN pg_namespace n ON n.oid=t.relnamespace ";
        collect(c, owned, result, prefix + "'TABLE:' || t.relkind::text || ':' || t.relrowsecurity || ':' || t.relforcerowsecurity || ':' || t.relpersistence::text" + from + "WHERE t.relkind IN ('r','p')");
        collect(c, owned, result, prefix + "'COLUMN:' || a.attname || ':' || format_type(a.atttypid,a.atttypmod) || ':' || a.attnotnull || ':' || COALESCE(pg_get_expr(d.adbin,d.adrelid),'') || ':' || a.attidentity::text || ':' || a.attgenerated::text"
                + from + "JOIN pg_attribute a ON a.attrelid=t.oid LEFT JOIN pg_attrdef d ON d.adrelid=t.oid AND d.adnum=a.attnum WHERE a.attnum>0 AND NOT a.attisdropped AND t.relkind IN ('r','p')");
        collect(c, owned, result, prefix + "'CONSTRAINT:' || k.conname || ':' || pg_get_constraintdef(k.oid) || ':' || k.convalidated"
                + from + "JOIN pg_constraint k ON k.conrelid=t.oid");
        collect(c, owned, result, prefix + "'INDEX:' || pg_get_indexdef(i.indexrelid)" + from + "JOIN pg_index i ON i.indrelid=t.oid");
        collect(c, owned, result, prefix + "'TRIGGER:' || pg_get_triggerdef(g.oid)" + from + "JOIN pg_trigger g ON g.tgrelid=t.oid WHERE NOT g.tgisinternal");
        return result;
    }
    private static void collect(Connection c, Set<String> owned, Set<String> rows, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            while (r.next()) {
                String key = r.getString(1) + "/" + r.getString(2);
                if (owned.contains(key)) rows.add(key + "/" + r.getString(3));
            }
        }
    }
}
