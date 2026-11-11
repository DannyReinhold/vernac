// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.experiments.tenancy;

import java.net.URI;
import java.sql.DriverManager;
import java.util.UUID;
import javax.sql.DataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Creates a disposable database and two genuinely distinct non-superuser database roles. */
final class PostgresFixture implements AutoCloseable {
    static final UUID A = UUID.fromString("00000000-0000-4000-8000-000000000001");
    static final UUID B = UUID.fromString("00000000-0000-4000-8000-000000000002");
    static final UUID INVOICE_A = UUID.fromString("10000000-0000-4000-8000-000000000001");
    static final UUID INVOICE_B = UUID.fromString("10000000-0000-4000-8000-000000000002");
    final String adminUrl;
    final String adminUser;
    final String adminPassword;
    final String database = "vernac_tenant_spike_" + UUID.randomUUID().toString().replace("-", "");
    final String owner = "vts_owner_" + UUID.randomUUID().toString().replace("-", "");
    final String runtime = "vts_runtime_" + UUID.randomUUID().toString().replace("-", "");
    final String rolePassword = UUID.randomUUID().toString().replace("-", "");
    final String url;
    HikariDataSource pool;

    PostgresFixture() throws Exception {
        adminUrl = required("VERNAC_PG_TEST_URL");
        adminUser = required("VERNAC_PG_TEST_USER");
        adminPassword = required("VERNAC_PG_TEST_PASSWORD");
        URI parsed = URI.create(adminUrl.substring("jdbc:".length()));
        if (!"postgresql".equals(parsed.getScheme()) || parsed.getHost() == null) {
            throw new IllegalArgumentException("A TCP jdbc:postgresql://host:port/database test URL is required");
        }
        url = "jdbc:" + new URI(parsed.getScheme(), null, parsed.getHost(), parsed.getPort(),
                "/" + database, parsed.getQuery(), null);
        try (var admin = DriverManager.getConnection(adminUrl, adminUser, adminPassword);
             var statement = admin.createStatement()) {
            // Names and temporary passwords are generated hexadecimal identifiers, never user interpolation.
            statement.execute("CREATE ROLE " + owner + " LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '" + rolePassword + "'");
            statement.execute("CREATE ROLE " + runtime + " LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '" + rolePassword + "'");
            statement.execute("CREATE DATABASE " + database);
        }
        try {
            admin("CREATE SCHEMA tenant_spike AUTHORIZATION " + owner);
            try (var connection = ownerSource().getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
            }
            admin("GRANT USAGE ON SCHEMA tenant_spike TO " + runtime);
            admin("GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_spike.invoice, tenant_spike.line TO " + runtime);
            admin("GRANT SELECT ON tenant_spike.catalog TO " + runtime);
            pool = pool(runtime);
        } catch (Exception failed) {
            close();
            throw failed;
        }
    }

    HikariDataSource pool(String role) {
        var config = new HikariConfig();
        config.setJdbcUrl(url); config.setUsername(role); config.setPassword(rolePassword);
        config.setMaximumPoolSize(2); config.setMinimumIdle(0); config.setConnectionTimeout(3000);
        config.setPoolName("tenant-spike");
        return new HikariDataSource(config);
    }

    DataSource ownerSource() { return new DriverManagerDataSource(url, owner, rolePassword); }
    DataSource adminSource() { return new DriverManagerDataSource(url, adminUser, adminPassword); }
    void admin(String sql) { new JdbcTemplate(adminSource()).execute(sql); }

    void reset() throws Exception {
        try (var connection = ownerSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("TRUNCATE tenant_spike.line, tenant_spike.invoice");
            }
            for (var tenant : new UUID[]{A, B}) {
                try (var setting = connection.prepareStatement("SELECT set_config('vernac.tenant_id', ?, true)")) {
                    setting.setString(1, tenant.toString()); setting.execute();
                }
                try (var insert = connection.prepareStatement("INSERT INTO tenant_spike.invoice VALUES (?, ?, ?, 1)")) {
                    insert.setObject(1, tenant.equals(A) ? INVOICE_A : INVOICE_B);
                    insert.setObject(2, tenant); insert.setString(3, tenant.equals(A) ? "A title" : "B title"); insert.executeUpdate();
                }
            }
            connection.commit();
        }
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Set " + key + " for the disposable PostgreSQL spike; tests must not be skipped");
        }
        return value;
    }

    @Override public void close() throws Exception {
        if (pool != null) pool.close();
        try (var admin = DriverManager.getConnection(adminUrl, adminUser, adminPassword);
             var statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            statement.execute("DROP ROLE IF EXISTS " + runtime);
            statement.execute("DROP ROLE IF EXISTS " + owner);
        }
    }
}
