// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;
import static org.junit.jupiter.api.Assertions.*;
import java.net.InetAddress;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.vernac.saas.identity.domain.AccountId;
class IdentityInvitationChainTest extends IdentityJourneyChainTest {
    static class Invitations extends Actions implements IdentityInvitationActions {
        public void requestInvitation(AccountId account,String address,Collection<String> roles,InetAddress source){calls.incrementAndGet();}
        public boolean acceptInvitation(String token,InetAddress source){calls.incrementAndGet();return result;}
    }
    @Configuration(proxyBeanMethods=false) @EnableWebSecurity
    static class InvitationApplication extends Application {@Override @Bean Actions actions(){return new Invitations();}}
    @Override @BeforeEach void start(){context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());context.register(InvitationApplication.class);context.refresh();chain=context.getBean("springSecurityFilterChain",jakarta.servlet.Filter.class);actions=context.getBean(Actions.class);}
    @Test void invitationGetScrubsCapabilityButAnonymousPostCannotAccept()throws Exception{
        var get=request("GET","/auth/invitation",null);var response=dispatch(get);assertEquals(200,response.getStatus());assertTrue(response.getContentAsString().contains("history.replaceState"));assertFalse(response.getContentAsString().contains("name=\"password\""));
        var post=request("POST","/auth/invitation",(MockHttpSession)get.getSession(false));body(post,form(Map.of("token","synthetic-token","_csrf",csrf(response))));assertEquals(401,dispatch(post).getStatus());assertEquals(0,actions.calls.get());
    }
    @Test void explicitAcceptanceRetainsCurrentIdentityAndRequiresDeliberateAccountSelection()throws Exception{
        var session=login();var get=request("GET","/auth/invitation",session);var page=dispatch(get);var post=request("POST","/auth/invitation",session);body(post,form(Map.of("token","synthetic-token","_csrf",csrf(page))));var response=dispatch(post);
        assertEquals(303,response.getStatus());assertEquals("/auth/accounts",response.getHeader("Location"));assertFalse(session.isInvalid());assertNotNull(new HttpSessionSecurityContextRepository().loadDeferredContext(post).get().getAuthentication());assertEquals(1,actions.calls.get());
    }
}
