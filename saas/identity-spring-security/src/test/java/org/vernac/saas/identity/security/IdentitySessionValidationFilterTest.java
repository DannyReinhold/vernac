// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.vernac.saas.identity.domain.UserId;
class IdentitySessionValidationFilterTest {
 static final Instant NOW=IdentitySessionTrustTest.NOW;
 @AfterEach void clear(){SecurityContextHolder.clearContext();}
 static IdentitySessionValidationFilter filter(long epoch) {
  return new IdentitySessionValidationFilter(new IdentitySessionTrust(id->OptionalLong.of(epoch),Clock.fixed(NOW,ZoneOffset.UTC)));
 }
 static void authenticated(long epoch,Instant at) {
  SecurityContextHolder.getContext().setAuthentication(IdentitySessionTrustTest.authentication(UserId.create(),epoch,at));
 }
 @Test void validAuthenticationContinuesWithoutCreatingOrReplacingASession() throws Exception {
  authenticated(0,NOW);var before=SecurityContextHolder.getContext().getAuthentication();
  var request=new MockHttpServletRequest();var response=new MockHttpServletResponse();var called=new AtomicBoolean();
  filter(0).doFilter(request,response,(req,res)->{called.set(true);assertSame(before,SecurityContextHolder.getContext().getAuthentication());});
  assertTrue(called.get());assertNull(request.getSession(false));assertEquals(200,response.getStatus());
 }
 @Test void staleOrAbsolutelyExpiredSessionIsInvalidatedBeforeDownstreamRuns() throws Exception {
  for(boolean expired:new boolean[]{false,true}){
   authenticated(expired?0:1,expired?NOW.minus(Duration.ofHours(12)):NOW);
   var request=new MockHttpServletRequest();var session=new MockHttpSession();request.setSession(session);
   var response=new MockHttpServletResponse();var called=new AtomicBoolean();
   filter(0).doFilter(request,response,(req,res)->{called.set(true);assertNull(SecurityContextHolder.getContext().getAuthentication());assertTrue(session.isInvalid());});
   assertTrue(called.get());assertEquals("no-store",response.getHeader("Cache-Control"));
  }
 }
 @Test void backendFailureReturnsSafe503WithoutDestroyingThePersistedSession() throws Exception {
  authenticated(0,NOW);var request=new MockHttpServletRequest();var session=new MockHttpSession();request.setSession(session);
  var response=new MockHttpServletResponse();
  var filter=new IdentitySessionValidationFilter(new IdentitySessionTrust(id->{throw new IllegalStateException("synthetic JDBC password");},Clock.fixed(NOW,ZoneOffset.UTC)));
  filter.doFilter(request,response,(req,res)->fail("Technical authentication failure reached handler"));
  assertEquals(503,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));
  assertFalse(session.isInvalid());assertNull(SecurityContextHolder.getContext().getAuthentication());
  assertEquals("Identity authentication unavailable",response.getContentAsString());
 }
 @Test void unrelatedAndAnonymousPathsKeepTheirExistingAuthenticationHandling() throws Exception {
  for(boolean technical:new boolean[]{false,true}){
   if(technical)SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("technical",null,List.of()));
   else SecurityContextHolder.clearContext();
   var original=SecurityContextHolder.getContext().getAuthentication();
   var request=new MockHttpServletRequest();var called=new AtomicBoolean();
   filter(0).doFilter(request,new MockHttpServletResponse(),(req,res)->{called.set(true);assertSame(original,SecurityContextHolder.getContext().getAuthentication());});
   assertTrue(called.get());assertNull(request.getSession(false));
  }
 }
}
