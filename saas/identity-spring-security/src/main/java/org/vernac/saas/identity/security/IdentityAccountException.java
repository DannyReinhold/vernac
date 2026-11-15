// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;
/** Stable safe transport category; never includes input identities, addresses, SQL or causes. */
public class IdentityAccountException extends IllegalStateException {
    public enum Code { ACCESS_DENIED, LAST_OWNER, INVALID_INPUT, INVALID_ROLES, CAPACITY, CONFIGURATION, UNAVAILABLE }
    private final Code code;
    public IdentityAccountException(Code code){super("Identity account operation unavailable: "+code);this.code=java.util.Objects.requireNonNull(code);}
    public final Code code(){return code;}
}
