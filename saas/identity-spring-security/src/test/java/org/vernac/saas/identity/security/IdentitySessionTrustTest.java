// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;
import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.vernac.runtime.authorization.*;
import org.vernac.saas.identity.domain.UserId;
class IdentitySessionTrustTest {
 static final Instant NOW=Instant.parse("2026-11-13T12:00:00Z");
 static UsernamePasswordAuthenticationToken authentication(UserId id,long epoch,Instant at) {
  var token=UsernamePasswordAuthenticationToken.authenticated(new IdentityPrincipal(id),null,List.of());
  token.setDetails(new IdentitySessionStamp(epoch,at));return token;
 }
 @Test void stampsRoundTripWithoutGeneratedDomainObjectsOrExposedMetadata() throws Exception {
  var token=authentication(UserId.create(),3,NOW);var bytes=new ByteArrayOutputStream();
  try(var out=new ObjectOutputStream(bytes)){out.writeObject(token);}
  assertFalse(bytes.toString(StandardCharsets.ISO_8859_1).contains("org.vernac.saas.identity.domain.UserId"));
  try(var in=new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
   var restored=(UsernamePasswordAuthenticationToken)in.readObject();
   assertEquals(token.getPrincipal(),restored.getPrincipal());assertEquals(token.getDetails(),restored.getDetails());
   assertNull(restored.getCredentials());assertFalse(restored.toString().contains(token.getName()));
   assertEquals("IdentitySessionStamp[REDACTED]",restored.getDetails().toString());
  }
  assertThrows(IllegalArgumentException.class,()->new IdentitySessionStamp(2,0,NOW));
  assertThrows(IllegalArgumentException.class,()->new IdentitySessionStamp(-1,NOW));
 }
 @Test void absoluteExpiryFutureMissingAndAnonymousStampsNeverReadOrGrant() {
  AtomicInteger reads=new AtomicInteger();var trust=new IdentitySessionTrust(id->{reads.incrementAndGet();return OptionalLong.of(0);},Clock.fixed(NOW,ZoneOffset.UTC));
  var user=UserId.create();
  assertFalse(trust.current(authentication(user,0,NOW.minus(Duration.ofHours(12)))));
  assertFalse(trust.current(authentication(user,0,NOW.plusNanos(1))));
  var missing=authentication(user,0,NOW);missing.setDetails(null);assertFalse(trust.current(missing));
  assertFalse(trust.current(new AnonymousAuthenticationToken("synthetic","anonymous",List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ANONYMOUS")))));
  assertEquals(0,reads.get());assertTrue(trust.current(authentication(user,0,NOW.minusSeconds(1))));assertEquals(1,reads.get());
 }
 @Test void changedOrInactiveEpochDeniesAndBackendFailuresStayTechnicalAndSanitized() {
  AtomicLong epoch=new AtomicLong(1);var trust=new IdentitySessionTrust(id->epoch.get()<0?OptionalLong.empty():OptionalLong.of(epoch.get()),Clock.fixed(NOW,ZoneOffset.UTC));
  var token=authentication(UserId.create(),1,NOW);assertTrue(trust.current(token));
  epoch.set(2);assertFalse(trust.current(token));epoch.set(-1);assertFalse(trust.current(token));
  var broken=new IdentitySessionTrust(id->{throw new IllegalStateException("synthetic-backend-secret");},Clock.fixed(NOW,ZoneOffset.UTC));
  var error=assertThrows(AuthorizationException.class,()->broken.current(token));
  assertEquals(AuthorizationException.Code.AUTHORIZATION_UNAVAILABLE,error.code());assertNull(error.getCause());assertFalse(error.toString().contains("synthetic-backend-secret"));
 }
 @Test void actorResolutionRechecksTheEpochAndClearsItsRecursiveLookupGuard() {
  AtomicLong epoch=new AtomicLong(0);
  var trust=new IdentitySessionTrust(id->OptionalLong.of(epoch.get()),Clock.fixed(NOW,ZoneOffset.UTC));
  var resolver=new SessionIdentityActorResolver(trust);var user=UserId.create();
  SecurityContextHolder.getContext().setAuthentication(authentication(user,0,NOW));
  try {assertEquals(user.asString(),resolver.resolve().orElseThrow().subject());epoch.set(1);assertTrue(resolver.resolve().isEmpty());}
  finally {SecurityContextHolder.clearContext();}
  var recursive=new AtomicReference<IdentitySessionTrust>();
  var token=authentication(user,0,NOW);
  recursive.set(new IdentitySessionTrust(id->{recursive.get().current(token);return OptionalLong.of(0);},Clock.fixed(NOW,ZoneOffset.UTC)));
  var error=assertThrows(AuthorizationException.class,()->recursive.get().current(token));
  assertEquals(AuthorizationException.Code.AUTHORIZATION_CONFIGURATION_ERROR,error.code());
  assertThrows(AuthorizationException.class,()->recursive.get().current(token));
 }
}
