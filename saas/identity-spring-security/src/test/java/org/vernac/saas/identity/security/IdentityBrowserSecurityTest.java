// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.vernac.saas.identity.domain.UserId;

class IdentityBrowserSecurityTest {
    private AnnotationConfigWebApplicationContext context;
    private Filter chain;
    @BeforeEach void start() {
        context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());
        context.register(Application.class);context.refresh();chain=context.getBean("springSecurityFilterChain",Filter.class);
    }
    @AfterEach void stop() { context.close();SecurityContextHolder.clearContext(); }
    @Configuration(proxyBeanMethods=false) @EnableWebSecurity
    static class Application {
        @Bean AtomicLong epoch() { return new AtomicLong(); }
        @Bean AtomicInteger calls() { return new AtomicInteger(); }
        @Bean AuthenticationManager manager(AtomicInteger calls) {
            return input -> {
                calls.incrementAndGet();
                if(!"secret".equals(input.getCredentials()))throw new BadCredentialsException("synthetic secret");
                var result=UsernamePasswordAuthenticationToken.authenticated(new IdentityPrincipal(UserId.create()),null,List.of());
                result.setDetails(new IdentitySessionStamp(0,Instant.parse("2026-11-15T12:00:00Z")));return result;
            };
        }
        @Bean IdentitySessionTrust trust(AtomicLong epoch) {
            return new IdentitySessionTrust(id->{if(epoch.get()==-2)throw new IllegalStateException("synthetic DB secret");
                return epoch.get()<0?OptionalLong.empty():OptionalLong.of(epoch.get());},
                Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"),ZoneOffset.UTC));
        }
        @Bean SecurityFilterChain security(HttpSecurity http,AuthenticationManager manager,IdentitySessionTrust trust) throws Exception {
            return IdentityBrowserSecurity.build(http,manager,trust,new IdentityLocalReturnTarget("/workspace"));
        }
    }
    private MockHttpServletRequest request(String method,String path,MockHttpSession session) {
        var request=new MockHttpServletRequest(method,path);request.setServletPath(path);request.setSecure(true);
        request.setScheme("https");request.setServerPort(443);request.setRemoteAddr("192.0.2.5");
        if(session!=null){request.setSession(session);request.setRequestedSessionId(session.getId());request.setRequestedSessionIdValid(true);}
        return request;
    }
    private MockHttpServletResponse dispatch(MockHttpServletRequest request) throws Exception {
        var response=new MockHttpServletResponse();
        chain.doFilter(request,response,(raw,out)->{
            if(request.getServletPath().equals("/identity/login") && request.getMethod().equals("GET")){
                var csrf=(CsrfToken)raw.getAttribute(CsrfToken.class.getName());out.getWriter().write(csrf.getToken());
            }else out.getWriter().write("protected");
        });return response;
    }
    private void body(MockHttpServletRequest request,String text) {
        request.setContentType("application/x-www-form-urlencoded");request.setContent(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private record Login(MockHttpSession session,String oldCsrf) {}
    private Login login() throws Exception {
        var page=request("GET","/identity/login",null);String token=dispatch(page).getContentAsString();
        var session=(MockHttpSession)page.getSession(false);String before=session.getId();
        var post=request("POST","/identity/login",session);body(post,"address=a%40example.test&password=secret&_csrf="+token);
        var response=dispatch(post);assertEquals(303,response.getStatus());assertEquals("/workspace",response.getHeader("Location"));
        assertNotEquals(before,session.getId());return new Login(session,token);
    }

    @Test void configuredChainRotatesIdPersistsAuthenticationAndClearsPreloginCsrf() throws Exception {
        var logged=login();
        assertEquals("protected",dispatch(request("GET","/workspace",logged.session())).getContentAsString());
        var logout=request("POST","/identity/logout",logged.session());body(logout,"_csrf="+logged.oldCsrf());
        assertEquals(403,dispatch(logout).getStatus());assertFalse(logged.session().isInvalid());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test void onlyCsrfProtectedPostLogsOutAndInvalidatesTheSession() throws Exception {
        var logged=login();
        assertEquals(200,dispatch(request("GET","/identity/logout",logged.session())).getStatus());assertFalse(logged.session().isInvalid());
        var page=request("GET","/identity/login",logged.session());String token=dispatch(page).getContentAsString();
        var logout=request("POST","/identity/logout",logged.session());body(logout,"_csrf="+token);
        var response=dispatch(logout);assertEquals(204,response.getStatus());assertTrue(logged.session().isInvalid());
        assertEquals(401,dispatch(request("GET","/workspace",null)).getStatus());
    }

    @Test void staleSessionsAreRemovedBeforeHandlersWhileBackendFailuresRetainThem() throws Exception {
        var logged=login();context.getBean(AtomicLong.class).set(-2);
        var response=dispatch(request("GET","/workspace",logged.session()));assertEquals(503,response.getStatus());
        assertEquals("Identity authentication unavailable",response.getContentAsString());assertFalse(logged.session().isInvalid());
        context.getBean(AtomicLong.class).set(1);response=dispatch(request("GET","/workspace",logged.session()));
        assertEquals(401,response.getStatus());assertTrue(logged.session().isInvalid());assertFalse(response.getContentAsString().contains("protected"));
    }

    @Test void insecureAndUnauthenticatedRequestsDoNotCreateSessionsOrSaveReturnUrls() throws Exception {
        var insecure=request("GET","/identity/login",null);insecure.setSecure(false);insecure.setScheme("http");insecure.addHeader("Host","evil.test");
        var response=dispatch(insecure);assertEquals(400,response.getStatus());assertNull(response.getHeader("Location"));assertNull(insecure.getSession(false));
        var protectedRequest=request("GET","/workspace",null);response=dispatch(protectedRequest);
        assertEquals(401,response.getStatus());assertNull(protectedRequest.getSession(false));
    }

    @Test void csrfAndBodyLimitsPrecedeLoginAndNoDefaultSpringLoginIsEnabled() throws Exception {
        var post=request("POST","/identity/login",null);body(post,"address=a&password=secret");
        assertEquals(403,dispatch(post).getStatus());
        post=request("POST","/identity/login",null);body(post,"password="+"x".repeat(17000));
        assertEquals(413,dispatch(post).getStatus());assertNull(post.getSession(false));
        var defaultLogin=request("POST","/login",null);body(defaultLogin,"username=a&password=secret");
        assertEquals(403,dispatch(defaultLogin).getStatus());assertEquals(0,context.getBean(AtomicInteger.class).get());
    }
}
