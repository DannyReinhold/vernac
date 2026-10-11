// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.ports;

/** Observed failure kind. Neither responsibility nor safe retry follows from this alone. */
public enum IntegrationCategory {
    CONFIGURATION, INVALID_REQUEST, INVALID_RESPONSE, AUTHENTICATION, AUTHORIZATION,
    RATE_LIMITED, REMOTE_FAILURE, UNEXPECTED_STATUS, TIMEOUT, COMMUNICATION, INTERRUPTED
}
