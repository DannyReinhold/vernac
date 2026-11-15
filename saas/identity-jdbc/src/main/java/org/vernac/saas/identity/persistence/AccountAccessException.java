// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;
/** Safe metadata boundary, sharing only stable categories with the browser adapter. */
public final class AccountAccessException extends org.vernac.saas.identity.security.IdentityAccountException {
    public AccountAccessException(Code code){super(code);}
}
