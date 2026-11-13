// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;
import java.net.InetAddress;
import java.util.Objects;
import org.vernac.saas.identity.security.credentials.*;
/** Transient redacted input. The source MUST come from trusted transport metadata, never a form/header. */
public final class IdentityLoginInput {
 private final LoginAddress address; private final InetAddress source;
 public IdentityLoginInput(String address,InetAddress trustedSource) {
  this.source=Objects.requireNonNull(trustedSource);
  LoginAddress parsed;
  try{parsed=LoginAddress.parse(address);}catch(CredentialInputException invalid){parsed=null;}
  this.address=parsed;
 }
 public LoginAddress address(){return address;}
 public InetAddress source(){return source;}
 @Override public String toString(){return "IdentityLoginInput[REDACTED]";}
}
