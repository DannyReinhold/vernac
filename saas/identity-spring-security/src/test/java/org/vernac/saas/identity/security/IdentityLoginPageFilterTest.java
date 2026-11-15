// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.web.csrf.*;

class IdentityLoginPageFilterTest {
    private final IdentityLoginPageFilter page=new IdentityLoginPageFilter(new IdentityLocalReturnTarget("/workspace"));
    private MockHttpServletRequest request() {
        var request=new MockHttpServletRequest("GET","/identity/login");request.setServletPath("/identity/login");
        request.setAttribute(CsrfToken.class.getName(),new DefaultCsrfToken("X-CSRF-TOKEN","_csrf","token\"<&"));return request;
    }
    @Test void rendersAccessibleCsrfFormWithoutVisitorSecretsOrExternalResources() throws Exception {
        var request=request();request.setContextPath("/app");request.setParameter("returnTo","/projects/42");
        request.setParameter("address","private@example.test");request.setParameter("password","private-password");
        var response=new MockHttpServletResponse();page.doFilter(request,response,(a,b)->fail());
        var html=response.getContentAsString();
        assertTrue(html.contains("action=\"/app/identity/login\""));assertTrue(html.contains("name=\"_csrf\" value=\"token&quot;&lt;&amp;\""));
        assertTrue(html.contains("name=\"returnTo\" value=\"/projects/42\""));assertTrue(html.contains("autocomplete=\"current-password\""));
        assertFalse(html.contains("private@example.test"));assertFalse(html.contains("private-password"));
        assertEquals("no-store",response.getHeader("Cache-Control"));assertEquals("no-referrer",response.getHeader("Referrer-Policy"));
        assertTrue(response.getHeader("Content-Security-Policy").contains("form-action 'self'"));
    }
    @Test void invalidReturnTargetsAreNotReflectedAndMissingCsrfFailsClosed() throws Exception {
        var request=request();request.setParameter("returnTo","//evil.test/\"><script>");var response=new MockHttpServletResponse();
        page.doFilter(request,response,(a,b)->fail());assertFalse(response.getContentAsString().contains("evil.test"));
        assertTrue(response.getContentAsString().contains("name=\"returnTo\" value=\"/workspace\""));
        request=request();request.removeAttribute(CsrfToken.class.getName());response=new MockHttpServletResponse();
        page.doFilter(request,response,(a,b)->fail());assertEquals(503,response.getStatus());assertEquals("Identity form unavailable",response.getContentAsString());
    }
    @Test void postAndUnrelatedPagesRemainDownstreamResponsibilities() throws Exception {
        for(boolean post:new boolean[]{false,true}) {
            var request=request();if(post)request.setMethod("POST");else request.setServletPath("/product");
            var called=new AtomicBoolean();page.doFilter(request,new MockHttpServletResponse(),(a,b)->called.set(true));assertTrue(called.get());
        }
    }
}
