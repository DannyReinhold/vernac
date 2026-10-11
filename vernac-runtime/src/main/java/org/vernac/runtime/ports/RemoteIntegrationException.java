// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.ports;

import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** A remote failure or violation of the expected response contract. */
@NullMarked
public final class RemoteIntegrationException extends PortInvocationException {
    public RemoteIntegrationException(AdapterInvocation invocation, IntegrationCategory category, String message) {
        super(invocation, category, message);
    }
    public RemoteIntegrationException(AdapterInvocation invocation, IntegrationCategory category,
            String message, Map<String, String> transportDetails, @Nullable Throwable cause) {
        super(invocation, category, message, transportDetails, cause);
    }
}
