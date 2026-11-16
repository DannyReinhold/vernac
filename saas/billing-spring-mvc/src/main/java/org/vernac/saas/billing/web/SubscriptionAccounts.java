// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.billing.web;

import org.vernac.runtime.tenancy.TenantContext;
import org.vernac.saas.identity.domain.*;
import org.vernac.saas.identity.security.IdentityAccountActions;
import org.vernac.saas.subscriptions.SubscriptionLedger;

import java.util.*;

/**
 * Adds initial Free provisioning to explicit verified selection; existing/ended agreements never
 * reopen implicitly.
 */
public final class SubscriptionAccounts implements IdentityAccountActions {
    private final IdentityAccountActions delegate;
    private final SubscriptionLedger subscriptions;

    public SubscriptionAccounts(IdentityAccountActions delegate, SubscriptionLedger subscriptions) {
        this.delegate = Objects.requireNonNull(delegate);
        this.subscriptions = Objects.requireNonNull(subscriptions);
    }

    public TenantContext.Scope select(AccountId account) {
        try (var verified = delegate.select(account)) {
            /* Verify membership before the global metadata write. */
        }
        subscriptions.ensureFree(account);
        return delegate.select(
                account); // Recheck membership before exposing the actual product scope.
    }

    public List<AccountView> accounts() {
        return delegate.accounts();
    }

    public List<MemberView> members(AccountId account) {
        return delegate.members(account);
    }

    public List<InvitationView> invitations(AccountId account) {
        return delegate.invitations(account);
    }

    public List<String> availableRoles() {
        return delegate.availableRoles();
    }

    public AccountId createOrganization() {
        return delegate.createOrganization();
    }

    public void assignRoles(AccountId account, UserId user, Collection<String> roles) {
        delegate.assignRoles(account, user, roles);
    }

    public void removeMember(AccountId account, UserId user) {
        delegate.removeMember(account, user);
    }

    public void transferOwnership(AccountId account, UserId successor) {
        delegate.transferOwnership(account, successor);
    }

    public void closeAccount(AccountId account) {
        delegate.closeAccount(account);
    }

    public void revokeInvitation(AccountId account, InvitationId invitation) {
        delegate.revokeInvitation(account, invitation);
    }

    public void disableIdentity() {
        delegate.disableIdentity();
    }

    public void closeIdentity() {
        delegate.closeIdentity();
    }
}
