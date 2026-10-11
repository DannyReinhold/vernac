// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.ports;

import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** An established problem in the local integration. */
@NullMarked
public final class LocalIntegrationException extends PortInvocationException {
    public LocalIntegrationException(AdapterInvocation invocation, IntegrationCategory category, String message) {
        super(invocation, category, message);
    }
    public LocalIntegrationException(AdapterInvocation invocation, IntegrationCategory category,
            String message, Map<String, String> transportDetails, @Nullable Throwable cause) {
        super(invocation, category, message, transportDetails, cause);
    }
}
