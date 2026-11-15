// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.*;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.vernac.saas.identity.domain.UserId;

class IdentityLoginFilterTest {
    private final HttpSessionSecurityContextRepository contexts = new HttpSessionSecurityContextRepository();
    private final HttpSessionCsrfTokenRepository csrf = new HttpSessionCsrfTokenRepository();
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private IdentityLoginFilter login(AuthenticationManager manager) {
        return new IdentityLoginFilter("/identity/login", manager, contexts,
                new ChangeSessionIdAuthenticationStrategy(), new IdentityLocalReturnTarget("/workspace"));
    }
    private MockHttpServletRequest request(String fields, boolean withCsrf) {
        var request = new MockHttpServletRequest("POST", "/identity/login");
        request.setServletPath("/identity/login");request.setRemoteAddr("192.0.2.41");
        request.addHeader("X-Forwarded-For", "203.0.113.99");
        request.setContentType("application/x-www-form-urlencoded");
        if (withCsrf) {
            var token=csrf.generateToken(request);csrf.saveToken(token,request,new MockHttpServletResponse());
            fields += "&_csrf="+token.getToken();
        }
        request.setContent(fields.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return request;
    }
    private void chain(MockHttpServletRequest request, MockHttpServletResponse response, AuthenticationManager manager) throws Exception {
        var csrfFilter = new CsrfFilter(csrf);
        // Plain handler lets the fixture submit the repository's token; production can retain XOR rendering.
        csrfFilter.setRequestHandler(new CsrfTokenRequestAttributeHandler());
        List<Filter> filters=List.of(new SecurityContextHolderFilter(contexts),
                new IdentityFormFilter(Set.of("/identity/login")),csrfFilter,login(manager));
        var proxy=new FilterChainProxy(new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE,filters));
        proxy.doFilter(request,response,(a,b)->fail("login unexpectedly reached application handler"));
    }
    private Authentication success() {
        var result=UsernamePasswordAuthenticationToken.authenticated(new IdentityPrincipal(UserId.create()),null,List.of());
        result.setDetails(new IdentitySessionStamp(0,Instant.parse("2026-11-15T12:00:00Z")));return result;
    }

    @Test void realSpringChainRequiresCsrfRotatesSessionAndPersistsOnlyStampedIdentity() throws Exception {
        var seen=new AtomicReference<Authentication>();var result=success();
        var request=request("address=a%40example.test&password=secret&returnTo=%2Fprojects%2F42",true);
        String previous=request.getSession().getId();request.setRequestedSessionId(previous);request.setRequestedSessionIdValid(true);var response=new MockHttpServletResponse();
        chain(request,response,input->{seen.set(input);var principal=(IdentityLoginInput)input.getPrincipal();
            assertEquals("192.0.2.41",principal.source().getHostAddress());assertEquals("secret",input.getCredentials());return result;});
        assertEquals(303,response.getStatus());assertEquals("/projects/42",response.getHeader("Location"));
        assertNotEquals(previous,request.getSession().getId());assertEquals("no-store",response.getHeader("Cache-Control"));
        assertNull(seen.get().getCredentials());assertSame(result,contexts.loadDeferredContext(request).get().getAuthentication());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test void missingCsrfAndOversizedFormsNeverInvokeAuthentication() throws Exception {
        var calls=new AtomicInteger();AuthenticationManager manager=input->{calls.incrementAndGet();return success();};
        var response=new MockHttpServletResponse();chain(request("address=a&password=b",false),response,manager);assertEquals(403,response.getStatus());
        response=new MockHttpServletResponse();chain(request("password="+"x".repeat(17000),false),response,manager);assertEquals(413,response.getStatus());
        assertEquals(0,calls.get());
    }

    @Test void failuresAreNeutralAndThrottlingAndBackendErrorsAreSanitized() throws Exception {
        AuthenticationExceptionCase[] failures={
            new AuthenticationExceptionCase(new BadCredentialsException("synthetic identity detail"),401,"Identity authentication failed"),
            new AuthenticationExceptionCase(new IdentityAuthenticationThrottledException(99),429,"Identity authentication temporarily throttled"),
            new AuthenticationExceptionCase(new AuthenticationServiceException("synthetic database secret"),503,"Identity authentication unavailable")};
        for(var failure:failures){
            var request=request("address=a%40example.test&password=secret",true);var response=new MockHttpServletResponse();
            var captured=new AtomicReference<Authentication>();
            chain(request,response,input->{captured.set(input);throw failure.error();});
            assertEquals(failure.status(),response.getStatus());assertEquals(failure.message(),response.getContentAsString());
            assertEquals("no-store",response.getHeader("Cache-Control"));assertNull(captured.get().getCredentials());
            assertNull(contexts.loadDeferredContext(request).get().getAuthentication());
            if(failure.status()==429)assertEquals("99",response.getHeader("Retry-After"));
        }
    }

    @Test void absentBoundedPipelineInvalidTransportAndForeignPrincipalsFailClosed() throws Exception {
        var calls=new AtomicInteger();var filter=login(input->{calls.incrementAndGet();return success();});
        var response=new MockHttpServletResponse();filter.doFilter(request("password=secret",true),response,(a,b)->fail());
        assertEquals(503,response.getStatus());assertEquals(0,calls.get());
        var request=request("address=a%40example.test&password=secret",true);request.setRemoteAddr("host.example.invalid");
        response=new MockHttpServletResponse();chain(request,response,input->{calls.incrementAndGet();return success();});
        assertEquals(503,response.getStatus());assertEquals(0,calls.get());
        response=new MockHttpServletResponse();chain(request("address=a%40example.test&password=secret",true),response,
            input->UsernamePasswordAuthenticationToken.authenticated("technical",null,List.of()));
        assertEquals(503,response.getStatus());
    }

    @Test void unsafeReturnFallsBackAndThrottleHintsAreBounded() throws Exception {
        var response=new MockHttpServletResponse();chain(request("address=a%40example.test&password=secret&returnTo=%2F%2Fevil.test",true),response,input->success());
        assertEquals("/workspace",response.getHeader("Location"));
        assertEquals(1,new IdentityAuthenticationThrottledException(-1).retryAfterSeconds());
        assertEquals(86400,new IdentityAuthenticationThrottledException(Integer.MAX_VALUE).retryAfterSeconds());
    }

    private record AuthenticationExceptionCase(org.springframework.security.core.AuthenticationException error,int status,String message) {}
}
