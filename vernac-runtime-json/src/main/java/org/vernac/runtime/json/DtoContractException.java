// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.json;
import org.jspecify.annotations.NullMarked;
import org.vernac.runtime.VernacTechnicalException;
/** Invalid transport data. The path identifies the field; messages never include its value. */
@NullMarked
public final class DtoContractException extends VernacTechnicalException {
    private final String path;
    public DtoContractException(String path, String reason) { super(path + ": " + reason); this.path = path; }
    public String path() { return path; }
}
