// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime;

import java.util.List;
import org.jspecify.annotations.NullMarked;

/** An ordered, structurally immutable domain collection; element objects are not copied. */
@NullMarked
public interface DomainList<T> extends Iterable<T> {
    /** Unmodifiable ordered view, retaining duplicate entries and original element references. */
    List<T> asList();
}
