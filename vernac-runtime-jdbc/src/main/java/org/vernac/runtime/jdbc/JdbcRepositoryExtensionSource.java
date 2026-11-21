// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.jdbc;

import org.vernac.runtime.AggregateRoot;

/**
 * Infrastructure-only adapter capability; the domain repository interface stays storage-neutral.
 */
public interface JdbcRepositoryExtensionSource<A extends AggregateRoot<?>, I> {
    JdbcRepositoryAccess<A, I> jdbcExtensions();
}
