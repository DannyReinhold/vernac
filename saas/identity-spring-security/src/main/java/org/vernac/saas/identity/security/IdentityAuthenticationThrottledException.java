// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;

import org.springframework.security.core.AuthenticationException;

/** Safe global/source admission denial; never use for subject-only throttling. */
public class IdentityAuthenticationThrottledException extends AuthenticationException {
    private final int retryAfterSeconds;
    public IdentityAuthenticationThrottledException(int seconds) {
        super("Identity authentication temporarily throttled");
        retryAfterSeconds = Math.max(1, Math.min(86400, seconds));
    }
    public final int retryAfterSeconds() { return retryAfterSeconds; }
}
