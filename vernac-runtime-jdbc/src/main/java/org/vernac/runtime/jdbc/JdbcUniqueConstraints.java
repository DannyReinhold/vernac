// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.jdbc;

import org.jspecify.annotations.NullMarked;
import org.postgresql.util.PSQLException;
import org.springframework.dao.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.vernac.runtime.persistence.UniqueConstraintViolationException;

import java.sql.SQLException;
import java.util.*;

/** Uses Spring classification and structured PostgreSQL fields; never parses error text/DETAIL. */
@NullMarked
final class JdbcUniqueConstraints {
    private final NamedParameterJdbcTemplate jdbc;
    private final List<JdbcMapping.UniqueConstraint> constraints;

    JdbcUniqueConstraints(
            NamedParameterJdbcTemplate jdbc, List<JdbcMapping.UniqueConstraint> constraints) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.constraints = List.copyOf(constraints);
    }

    void requireBound() {
        if (constraints.isEmpty()) return;
        var holder = holder();
        try {
            if (holder.getConnection().getAutoCommit()) throw boundary();
        } catch (SQLException unavailable) {
            throw new DataAccessResourceFailureException("Unique aggregate writer unavailable");
        }
    }

    void requireWriter() {
        requireBound();
        if (!constraints.isEmpty()
                && TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw boundary();
    }

    void rethrowRecognized(DataAccessException failure) {
        if (constraints.isEmpty()) return;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure;
                cause != null && seen.size() < 64 && seen.add(cause);
                cause = cause.getCause()) {
            if (!(cause instanceof PSQLException postgres)) continue;
            if (failure instanceof DuplicateKeyException
                    && "23505".equals(postgres.getSQLState())) {
                var error = postgres.getServerErrorMessage();
                if (error == null) continue;
                for (var key : constraints) {
                    if (key.schema().equals(error.getSchema())
                            && key.table().equals(error.getTable())
                            && key.name().equals(error.getConstraint())) {
                        holder().setRollbackOnly();
                        throw new UniqueConstraintViolationException(key.aggregate(), key.field());
                    }
                }
            }
            // PostgreSQL program/index-size limits are technical failure, never a business-key
            // conflict.
            if ("54000".equals(postgres.getSQLState())) {
                holder().setRollbackOnly();
                throw new DataAccessResourceFailureException(
                        "Aggregate storage capacity unavailable");
            }
        }
    }

    private ConnectionHolder holder() {
        var source = Objects.requireNonNull(jdbc.getJdbcTemplate().getDataSource());
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || !(TransactionSynchronizationManager.getResource(source)
                        instanceof ConnectionHolder holder)) throw boundary();
        return holder;
    }

    private static IllegalTransactionStateException boundary() {
        return new IllegalTransactionStateException(
                "Unique aggregate persistence requires its bound writer transaction");
    }
}
