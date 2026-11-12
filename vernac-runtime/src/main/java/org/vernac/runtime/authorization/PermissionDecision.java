// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.authorization;

/** Side-effect-free result. DENY uses the safe ACCESS_DENIED category; failures throw. */
public enum PermissionDecision { ALLOW, DENY }
