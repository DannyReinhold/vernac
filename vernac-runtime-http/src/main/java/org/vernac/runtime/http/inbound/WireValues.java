// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.http.inbound;

import org.jspecify.annotations.NullMarked;
import org.vernac.runtime.WireScalars;

/** HTTP classification around the shared, exact scalar parser. */
@NullMarked
public final class WireValues {
    private WireValues() {}
    public static Object parse(String type, String value) {
        try { return WireScalars.parse(type, value); }
        catch (IllegalArgumentException invalid) { throw new WireValueException(type); }
    }
}
