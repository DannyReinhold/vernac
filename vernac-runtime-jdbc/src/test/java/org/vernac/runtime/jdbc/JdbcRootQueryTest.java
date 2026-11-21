// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class JdbcRootQueryTest {
    @Test
    void snapshotsParameterCollectionsAndRedactsSqlAndBusinessValues() {
        var ids = new ArrayList<>(List.of(UUID.randomUUID()));
        var parameters = new HashMap<String, Object>();
        parameters.put("ids", ids);
        parameters.put("title", "private customer value");
        var query =
                JdbcRootQuery.of(
                        "SELECT id FROM private.table WHERE title=:title AND id IN (:ids)",
                        parameters);
        ids.clear();
        parameters.clear();
        assertEquals(1, ((List<?>) query.parameters().getValue("ids")).size());
        assertEquals("private customer value", query.parameters().getValue("title"));
        assertFalse(query.toString().contains("private"));
        assertFalse(query.toString().contains("customer"));
        assertThrows(
                RepositoryExtensionException.class,
                () -> JdbcRootQuery.of("SELECT id", Map.of("__vernacTenant", UUID.randomUUID())));
        assertThrows(
                RepositoryExtensionException.class,
                () -> JdbcRootQuery.of("SELECT id", Map.of("ids", Set.of(UUID.randomUUID()))));
    }

    @Test
    void cursorContextsBindNullAndInListFiltersWithoutChangingScalarEncoding() {
        var one =
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                        .addValue("names", List.of("one", "two"))
                        .addValue("missing", null);
        var two =
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                        .addValue("names", List.of("two", "one"))
                        .addValue("missing", null);
        var sorting = List.of(new JdbcCursorSort("UUID", List.of("id"), false));
        assertFalse(
                Arrays.equals(
                        JdbcCursorCodec.context("test", "model", "table", "where", sorting, one),
                        JdbcCursorCodec.context("test", "model", "table", "where", sorting, two)));
    }
}
