// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;
import java.util.*;
import org.vernac.runtime.authorization.*;
import org.vernac.runtime.authorization.spring.SpringSecurityActorResolver;
/** Explicit browser actor mapping: require a current server-created stamp on every invocation. */
public final class SessionIdentityActorResolver implements ActorResolver {
 private final SpringSecurityActorResolver delegate;
 public SessionIdentityActorResolver(IdentitySessionTrust trust) {
  Objects.requireNonNull(trust);
  delegate=new SpringSecurityActorResolver(authentication->{
   if(!trust.current(authentication))return Optional.empty();
   var principal=(IdentityPrincipal)authentication.getPrincipal();
   return Optional.of(new VerifiedActor(IdentityActorResolver.REALM,principal.userId().asString(),VerifiedActor.Kind.HUMAN));
  });
 }
 @Override public Optional<VerifiedActor> resolve(){return delegate.resolve();}
}
