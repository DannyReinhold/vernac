// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;
import java.time.*;
import java.util.*;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.vernac.runtime.authorization.AuthorizationException;
import org.vernac.saas.identity.domain.UserId;
/** Fresh eligibility/epoch proof for server-created Spring Authentication details. No cache or session protocol. */
public final class IdentitySessionTrust {
 @FunctionalInterface public interface EpochLookup { OptionalLong currentActiveEpoch(UserId subject); }
 public static final Duration ABSOLUTE_LIFETIME=Duration.ofHours(12);
 private final EpochLookup lookup; private final Clock clock;
 private final ThreadLocal<Boolean> checking=new ThreadLocal<>();
 public IdentitySessionTrust(EpochLookup lookup,Clock clock){this.lookup=Objects.requireNonNull(lookup);this.clock=Objects.requireNonNull(clock);}
 public boolean current(Authentication authentication) {
  if(authentication==null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken
   || !(authentication.getPrincipal() instanceof IdentityPrincipal principal)
   || !(authentication.getDetails() instanceof IdentitySessionStamp stamp))return false;
  Instant now=clock.instant();
  if(stamp.authenticatedAt().isAfter(now) || Duration.between(stamp.authenticatedAt(),now).compareTo(ABSOLUTE_LIFETIME)>=0)return false;
  if(Boolean.TRUE.equals(checking.get()))throw new AuthorizationException(AuthorizationException.Code.AUTHORIZATION_CONFIGURATION_ERROR);
  checking.set(true);
  try {
   OptionalLong epoch=lookup.currentActiveEpoch(principal.userId());
   if(epoch==null || (epoch.isPresent() && epoch.getAsLong()<0))
    throw new AuthorizationException(AuthorizationException.Code.AUTHORIZATION_CONFIGURATION_ERROR);
   return epoch.isPresent() && epoch.getAsLong()==stamp.epoch();
  } catch(RuntimeException unavailable) {
   if(unavailable instanceof AuthorizationException safe
    && (safe.code()==AuthorizationException.Code.AUTHORIZATION_CONFIGURATION_ERROR || safe.code()==AuthorizationException.Code.AUTHORIZATION_UNAVAILABLE))throw safe;
   throw new AuthorizationException(AuthorizationException.Code.AUTHORIZATION_UNAVAILABLE);
  } finally {checking.remove();}
 }
 @Override public String toString(){return "IdentitySessionTrust[REDACTED]";}
}
