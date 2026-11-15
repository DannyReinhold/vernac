// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;
import java.time.Instant;
import java.util.*;
import org.vernac.runtime.tenancy.TenantContext;
import org.vernac.saas.identity.domain.*;
/** Current human/account metadata port; never an unrestricted repository or caller-selected TenantId. */
public interface IdentityAccountActions {
    record AccountView(AccountId account,String kind,boolean owner){@Override public String toString(){return "AccountView[REDACTED]";}}
    record MemberView(UserId subject,boolean owner,boolean active,Set<String> roles,String address){public MemberView{roles=Set.copyOf(roles);}@Override public String toString(){return "MemberView[REDACTED]";}}
    record InvitationView(InvitationId invitation,String recipient,Instant expires,Set<String> roles){public InvitationView{roles=Set.copyOf(roles);}@Override public String toString(){return "InvitationView[REDACTED]";}}
    List<AccountView> accounts();List<MemberView> members(AccountId account);List<InvitationView> invitations(AccountId account);List<String> availableRoles();
    AccountId createOrganization();TenantContext.Scope select(AccountId account);
    void assignRoles(AccountId account,UserId subject,Collection<String> roles);void removeMember(AccountId account,UserId subject);
    void transferOwnership(AccountId account,UserId successor);void closeAccount(AccountId account);void revokeInvitation(AccountId account,InvitationId invitation);
    void disableIdentity();void closeIdentity();
}
