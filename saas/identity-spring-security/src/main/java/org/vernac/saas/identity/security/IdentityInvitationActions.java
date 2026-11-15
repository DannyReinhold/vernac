// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;
import java.net.InetAddress;
import java.util.Collection;
import org.vernac.saas.identity.domain.AccountId;
/** Issuance uses the existing admitted encrypted shared-provider path; acceptance requires the verified intended identity. */
public interface IdentityInvitationActions extends IdentityBrowserActions {
    void requestInvitation(AccountId account,String address,Collection<String> productRoles,InetAddress source);
    boolean acceptInvitation(String token,InetAddress source);
}
