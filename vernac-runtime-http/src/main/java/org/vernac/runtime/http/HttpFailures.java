// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.http;

import org.jspecify.annotations.NullMarked;
import org.vernac.runtime.ports.*;

/** Conservative defaults; a status alone does not prove a business rejection. */
@NullMarked
public final class HttpFailures {
    private HttpFailures() { }

    public static PortInvocationException defaultFailure(AdapterInvocation invocation, HttpResponseData response) {
        int status = response.status();
        var category = switch (status) {
            case 401 -> IntegrationCategory.AUTHENTICATION;
            case 403 -> IntegrationCategory.AUTHORIZATION;
            case 429 -> IntegrationCategory.RATE_LIMITED;
            default -> status >= 500 ? IntegrationCategory.REMOTE_FAILURE : IntegrationCategory.UNEXPECTED_STATUS;
        };
        String message = "HTTP adapter received status " + status;
        if (status >= 500) {
            return new RemoteIntegrationException(invocation, category, message, response.failureDetails(), null);
        }
        return new PortInvocationException(invocation, category, message, response.failureDetails(), null);
    }
}
