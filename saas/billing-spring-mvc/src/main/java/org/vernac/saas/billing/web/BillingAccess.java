// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.billing.web;

import org.vernac.saas.identity.domain.AccountId;
import org.vernac.saas.identity.security.*;

import java.util.Objects;

/**
 * One fresh metadata ownership policy, shared by UI and application payment/invoice authority
 * callbacks.
 */
public final class BillingAccess {
    private final IdentityAccountActions accounts;

    public BillingAccess(IdentityAccountActions accounts) {
        this.accounts = Objects.requireNonNull(accounts);
    }

    public void requireOwner(AccountId account) {
        if (accounts.accounts().stream()
                .noneMatch(row -> row.account().equals(account) && row.owner()))
            throw new IdentityAccountException(IdentityAccountException.Code.ACCESS_DENIED);
    }
}
