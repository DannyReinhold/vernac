// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime;

import org.jspecify.annotations.NullMarked;

/** Technical adapter state, deliberately absent from domain read/write views.
 * Version changes do not count as domain modifications. Transaction rollback handling
 * remains the persistence adapter's responsibility.
 */
@NullMarked
public final class PersistenceState {
    private long version;

    public PersistenceState(long version) {
        version(version);
    }

    public long version() { return version; }

    /** Updates the same aggregate's persisted version; does not replace its identity. */
    public void version(long version) {
        if (version < 0) throw new IllegalArgumentException("Persistence version must not be negative");
        this.version = version;
    }
}
