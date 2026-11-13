// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
/** Server-created technical session metadata; never a generated domain object or client credential. */
public record IdentitySessionStamp(int version,long epoch,Instant authenticatedAt) implements Serializable {
 @Serial private static final long serialVersionUID=1L;
 public IdentitySessionStamp(long epoch,Instant authenticatedAt){this(1,epoch,authenticatedAt);}
 public IdentitySessionStamp {
  if(version!=1 || epoch<0 || authenticatedAt==null)throw new IllegalArgumentException("Invalid identity session stamp");
 }
 @Override public String toString(){return "IdentitySessionStamp[REDACTED]";}
}
