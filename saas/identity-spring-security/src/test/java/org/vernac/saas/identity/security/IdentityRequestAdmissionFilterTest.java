// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;
import static org.junit.jupiter.api.Assertions.*;
import jakarta.servlet.http.HttpSession;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class IdentityRequestAdmissionFilterTest {
    private MockHttpServletRequest unreadable() {
        return new MockHttpServletRequest() {
            @Override public String getParameter(String name) { throw new AssertionError("parsed parameters before admission"); }
            @Override public HttpSession getSession(boolean create) { throw new AssertionError("loaded session before admission"); }
        };
    }
    @Test void denialNeverReadsParametersOrSessionsAndReturnsBoundedRetry() throws Exception {
        var response=new MockHttpServletResponse();
        new IdentityRequestAdmissionFilter(()->new IdentityRequestAdmission.Decision(false,60))
            .doFilter(unreadable(),response,(a,b)->fail());
        assertEquals(429,response.getStatus());assertEquals("60",response.getHeader("Retry-After"));
        assertEquals("Identity ingress temporarily throttled",response.getContentAsString());assertEquals("no-store",response.getHeader("Cache-Control"));
    }
    @Test void backendAndInvalidDecisionsFailClosedWithoutLeakingDetails() throws Exception {
        for(IdentityRequestAdmission gate:new IdentityRequestAdmission[]{()->{throw new IllegalStateException("synthetic SQL key");},()->null}) {
            var response=new MockHttpServletResponse();new IdentityRequestAdmissionFilter(gate).doFilter(unreadable(),response,(a,b)->fail());
            assertEquals(503,response.getStatus());assertEquals("Identity ingress unavailable",response.getContentAsString());
        }
        assertThrows(IllegalArgumentException.class,()->new IdentityRequestAdmission.Decision(false,0));
        assertThrows(IllegalArgumentException.class,()->new IdentityRequestAdmission.Decision(false,61));
        assertThrows(IllegalArgumentException.class,()->new IdentityRequestAdmission.Decision(true,1));
    }
    @Test void admittedRequestsContinueWithoutAdmissionInspectingVisitorData() throws Exception {
        var called=new AtomicBoolean();new IdentityRequestAdmissionFilter(()->new IdentityRequestAdmission.Decision(true,0))
            .doFilter(unreadable(),new MockHttpServletResponse(),(a,b)->called.set(true));assertTrue(called.get());
    }
}
