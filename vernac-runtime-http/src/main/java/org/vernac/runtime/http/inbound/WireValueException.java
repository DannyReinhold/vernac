// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.http.inbound;
import org.jspecify.annotations.NullMarked;
import org.vernac.runtime.VernacTechnicalException;
/** Transport parsing failure; does not capture the supplied value. */
@NullMarked
public final class WireValueException extends VernacTechnicalException {
    public WireValueException(String type) {super("Invalid HTTP wire value for " + type);}
}
