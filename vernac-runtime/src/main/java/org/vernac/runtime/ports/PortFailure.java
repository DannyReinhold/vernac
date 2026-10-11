// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.ports;

import java.util.Map;
import org.jspecify.annotations.NullMarked;

/** Common structured diagnostics for technical failures and business rejections. */
@NullMarked
public interface PortFailure {
    AdapterInvocation invocation();
    /** Explicitly selected transport facts; never an automatically captured payload. */
    Map<String, String> transportDetails();
}
