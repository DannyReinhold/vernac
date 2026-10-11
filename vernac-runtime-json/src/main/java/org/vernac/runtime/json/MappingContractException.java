// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.json;
import org.jspecify.annotations.*;
import org.vernac.runtime.VernacTechnicalException;
/** Conversion failure with its mapping and target path, without automatic request payload capture. */
@NullMarked
public final class MappingContractException extends VernacTechnicalException {
    private final String mapping;
    private final String path;
    public MappingContractException(String mapping,String path,@Nullable Throwable cause) {
        super("Mapping " + mapping + " failed at " + path,cause);this.mapping=mapping;this.path=path;
    }
    public String mapping() {return mapping;}
    public String path() {return path;}
}
