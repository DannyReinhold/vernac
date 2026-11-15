// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;
/** Safe metadata boundary; no identifiers, addresses, SQL, causes or provider messages. */
public final class AccountAccessException extends IllegalStateException {
    public enum Code { ACCESS_DENIED, LAST_OWNER, INVALID_ROLES, CAPACITY, CONFIGURATION, UNAVAILABLE }
    private final Code code;
    public AccountAccessException(Code code) { super("Identity account operation unavailable: "+code);this.code=java.util.Objects.requireNonNull(code); }
    public Code code() {return code;}
}
