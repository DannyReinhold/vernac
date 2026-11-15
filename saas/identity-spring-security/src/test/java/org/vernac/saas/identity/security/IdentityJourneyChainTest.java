// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;
import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.vernac.saas.identity.domain.UserId;
class IdentityJourneyChainTest {
    static final Instant NOW=Instant.parse("2026-11-15T12:00:00Z");
    static class Actions implements IdentityBrowserActions {
        final AtomicInteger calls=new AtomicInteger();IdentityActionException failure;InetAddress source;boolean result=true;
        private void called(InetAddress source){this.source=source;calls.incrementAndGet();if(failure!=null)throw failure;}
        public void requestRegistration(String address,InetAddress source){called(source);}
        public void requestRecovery(String address,InetAddress source){called(source);}
        public boolean verify(String token,String password,InetAddress source){called(source);return result;}
        public boolean recover(String token,String password,InetAddress source){called(source);return result;}
        public boolean changePassword(String current,String replacement,InetAddress source){called(source);return result;}
    }
    @Configuration(proxyBeanMethods=false) @EnableWebSecurity
    static class Application {
        @Bean Actions actions(){return new Actions();}
        @Bean AuthenticationManager manager(){return input->{var result=UsernamePasswordAuthenticationToken.authenticated(new IdentityPrincipal(UserId.create()),null,List.of());result.setDetails(new IdentitySessionStamp(0,NOW));return result;};}
        @Bean SecurityFilterChain security(HttpSecurity http,AuthenticationManager manager,Actions actions)throws Exception {
            return IdentityBrowserSecurity.build(http,manager,new IdentitySessionTrust(id->OptionalLong.of(0),Clock.fixed(NOW,ZoneOffset.UTC)),new IdentityLocalReturnTarget("/workspace"),()->new IdentityRequestAdmission.Decision(true,0),actions);
        }
    }
    AnnotationConfigWebApplicationContext context;Filter chain;Actions actions;
    @BeforeEach void start(){context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());context.register(Application.class);context.refresh();chain=context.getBean("springSecurityFilterChain",Filter.class);actions=context.getBean(Actions.class);}
    @AfterEach void stop(){context.close();SecurityContextHolder.clearContext();}
    MockHttpServletRequest request(String method,String path,MockHttpSession session){var r=new MockHttpServletRequest(method,path);r.setServletPath(path);r.setSecure(true);r.setScheme("https");r.setRemoteAddr("192.0.2.44");r.addHeader("X-Forwarded-For","203.0.113.5");if(session!=null){r.setSession(session);r.setRequestedSessionId(session.getId());r.setRequestedSessionIdValid(true);}return r;}
    MockHttpServletResponse dispatch(MockHttpServletRequest r)throws Exception{var response=new MockHttpServletResponse();chain.doFilter(r,response,(a,b)->b.getWriter().write("application"));return response;}
    static String csrf(MockHttpServletResponse response)throws Exception{var matcher=java.util.regex.Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(response.getContentAsString());assertTrue(matcher.find());return matcher.group(1);}
    static String form(Map<String,String> fields){return fields.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));}
    void body(MockHttpServletRequest r,String text){r.setContentType("application/x-www-form-urlencoded");r.setContent(text.getBytes(StandardCharsets.UTF_8));}
    MockHttpSession login()throws Exception{var get=request("GET","/identity/login",null);var page=dispatch(get);var post=request("POST","/identity/login",(MockHttpSession)get.getSession(false));body(post,form(Map.of("address","member@example.test","password","secret","_csrf",csrf(page))));assertEquals(303,dispatch(post).getStatus());return (MockHttpSession)post.getSession(false);}
    @Test void getNeverInvokesActionsAndBearerPagesScrubFragmentsBeforeFormActivation()throws Exception{
        for(String path:IdentityJourneyFilter.PUBLIC){var get=request("GET",path,null);get.setParameter("token","synthetic-bearer-secret");var response=dispatch(get);assertEquals(200,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertEquals("no-referrer",response.getHeader("Referrer-Policy"));assertFalse(response.getContentAsString().contains("synthetic-bearer-secret"));
            if(path.equals("/auth/verify") || path.equals("/auth/recover")){var html=response.getContentAsString();assertTrue(html.contains("name=\"token\" value=\"\""));assertTrue(html.contains("type=\"submit\" disabled"));assertTrue(html.indexOf("history.replaceState")<html.indexOf("document.addEventListener"));
                var script=html.substring(html.indexOf("<script>")+8,html.indexOf("</script>"));var hash=Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(script.getBytes(StandardCharsets.UTF_8)));assertTrue(response.getHeader("Content-Security-Policy").contains("'sha256-"+hash+"'"));assertFalse(html.contains("fetch("));}
        }
        assertEquals(0,actions.calls.get());assertEquals(401,dispatch(request("GET","/auth/password",null)).getStatus());
    }
    @Test void csrfBodyAndQueryLimitsPrecedeEveryPublicPostAction()throws Exception{
        for(String path:IdentityJourneyFilter.PUBLIC){var post=request("POST",path,null);body(post,"address=member%40example.test&password=secret&token=secret");assertEquals(403,dispatch(post).getStatus());}
        var tooLarge=request("POST","/auth/register",null);body(tooLarge,"address="+"x".repeat(17000));assertEquals(413,dispatch(tooLarge).getStatus());
        var query=request("POST","/auth/verify",null);query.setQueryString("token=secret");body(query,"token=secret");assertEquals(400,dispatch(query).getStatus());assertEquals(0,actions.calls.get());
    }
    @Test void neutralRequestsAndExplicitVerificationPostUseTransportSourceAndDoNotAutoLogin()throws Exception{
        for(String path:List.of("/auth/register","/auth/recovery-request")){var get=request("GET",path,null);var page=dispatch(get);var post=request("POST",path,(MockHttpSession)get.getSession(false));body(post,form(Map.of("address","member@example.test","_csrf",csrf(page))));var response=dispatch(post);assertEquals(202,response.getStatus());assertEquals("If the address is eligible, follow the instructions sent to it.",response.getContentAsString());}
        var session=login();var get=request("GET","/auth/verify",session);var page=dispatch(get);var post=request("POST","/auth/verify",session);body(post,form(Map.of("token","synthetic-token","password","synthetic-password","_csrf",csrf(page))));var response=dispatch(post);
        assertEquals(303,response.getStatus());assertEquals("/identity/login",response.getHeader("Location"));assertTrue(session.isInvalid());assertNull(SecurityContextHolder.getContext().getAuthentication());assertEquals("192.0.2.44",actions.source.getHostAddress());assertFalse(response.getContentAsString().contains("synthetic"));
    }
    @Test void safePolicyThrottleAndTechnicalFailuresDoNotInvalidateAnExistingSessionOrLeakInputs()throws Exception{
        for(var code:IdentityActionException.Code.values()){actions.failure=new IdentityActionException(code,91);var get=request("GET","/auth/recover",null);var page=dispatch(get);var session=(MockHttpSession)get.getSession(false);var post=request("POST","/auth/recover",session);body(post,form(Map.of("token","synthetic-secret","password","synthetic-password","_csrf",csrf(page))));var response=dispatch(post);
            assertEquals(switch(code){case PASSWORD_POLICY->400;case THROTTLED->429;case AUTHENTICATION_REQUIRED->401;case UNAVAILABLE->503;},response.getStatus());assertFalse(session.isInvalid());assertFalse(response.getContentAsString().contains("synthetic"));if(code==IdentityActionException.Code.THROTTLED)assertEquals("91",response.getHeader("Retry-After"));}
    }
    @Test void protectedPasswordChangeRequiresLoginAndInvalidatesSessionOnlyAfterSuccess()throws Exception{
        var session=login();var get=request("GET","/auth/password",session);var page=dispatch(get);assertEquals(200,page.getStatus());assertTrue(page.getContentAsString().contains("autocomplete=\"current-password\""));
        var post=request("POST","/auth/password",session);body(post,form(Map.of("currentPassword","old synthetic password","password","new synthetic password","_csrf",csrf(page))));assertEquals(303,dispatch(post).getStatus());assertTrue(session.isInvalid());
    }
}
