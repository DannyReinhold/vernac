// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;
import java.net.InetAddress;
/** Trusted transport adapter port. Secrets are transient inputs, never events, model values or logs. */
public interface IdentityBrowserActions {
    void requestRegistration(String address,InetAddress transportSource);
    void requestRecovery(String address,InetAddress transportSource);
    boolean verify(String token,String password,InetAddress transportSource);
    boolean recover(String token,String password,InetAddress transportSource);
    boolean changePassword(String currentPassword,String replacement,InetAddress transportSource);
}
