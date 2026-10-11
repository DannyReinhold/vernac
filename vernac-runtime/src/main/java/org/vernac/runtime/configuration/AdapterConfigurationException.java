// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.configuration;

import org.jspecify.annotations.NullMarked;
import org.vernac.runtime.VernacTechnicalException;

/** Invalid adapter configuration. Messages intentionally contain no property values or causes. */
@NullMarked
public final class AdapterConfigurationException extends VernacTechnicalException {
  public AdapterConfigurationException(String context, String property, String expected) {
    super(
        "Adapter configuration "
            + context
            + ": property '"
            + property
            + "' must supply "
            + expected
            + ". Value omitted.");
  }
}
