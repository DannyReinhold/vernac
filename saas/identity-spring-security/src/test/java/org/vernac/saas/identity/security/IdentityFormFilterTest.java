// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;

import static org.junit.jupiter.api.Assertions.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class IdentityFormFilterTest {
    private final IdentityFormFilter filter = new IdentityFormFilter(Set.of("/identity/login"));
    private MockHttpServletRequest request(String body) {
        var request = new MockHttpServletRequest("POST", "/identity/login");
        request.setServletPath("/identity/login");
        request.setContentType("application/x-www-form-urlencoded;charset=UTF-8");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    @Test void parsesBoundedUtf8WithoutInvokingContainerParameterParsing() throws Exception {
        var request = new MockHttpServletRequest("POST", "/identity/login") {
            @Override public String getParameter(String name) { throw new AssertionError("container parsed parameters"); }
            @Override public java.util.Map<String, String[]> getParameterMap() { throw new AssertionError("container parsed parameters"); }
        };
        request.setServletPath("/identity/login");request.setContentType("application/x-www-form-urlencoded");
        request.setContent("address=a%2Bb%40example.org&password=Gr%C3%BCn+baum&_csrf=token".getBytes(StandardCharsets.UTF_8));
        filter.doFilter(request, new MockHttpServletResponse(), (raw, response) -> {
            var form = (HttpServletRequest) raw;
            assertEquals("a+b@example.org", form.getParameter("address"));
            assertEquals("Grün baum", form.getParameter("password"));
            assertEquals("token", form.getParameter("_csrf"));
            assertNull(form.getParameter("absent"));
            form.getParameterValues("password")[0] = "changed";
            form.getParameterMap().get("password")[0] = "changed";
            assertEquals("Grün baum", form.getParameter("password"));
        });
    }

    @Test void preservesAllValidUnicodePasswordInputWithoutTrimmingOrReplacement() throws Exception {
        var response=new MockHttpServletResponse();
        filter.doFilter(request("password=+%00%0A%EF%BF%BD+"),response,(raw,out)->
            assertEquals(" \u0000\n\ufffd ",((HttpServletRequest)raw).getParameter("password")));
        assertEquals(200,response.getStatus());
    }

    @Test void rejectsOversizedKnownAndChunkedBodiesBeforeParametersAreRead() throws Exception {
        for (boolean unknown : new boolean[]{false,true}) {
            var request = new MockHttpServletRequest("POST", "/identity/login") {
                @Override public long getContentLengthLong() { return unknown ? -1 : super.getContentLengthLong(); }
                @Override public String getParameter(String name) { throw new AssertionError("container parsed parameters"); }
            };
            request.setServletPath("/identity/login");request.setContentType("application/x-www-form-urlencoded");
            request.setContent(new byte[IdentityFormFilter.MAX_BODY_BYTES+1]);
            var response = new MockHttpServletResponse();
            filter.doFilter(request,response,(a,b)->fail("oversized request reached handler"));
            assertEquals(413,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));
        }
    }

    @Test void rejectsAmbiguousMalformedAndUnboundedFields() throws Exception {
        for (String body : new String[]{"password=a&password=b", "password=%zz", "password=%C0%AF", "password=%E0%80%80", "a", "a=b&", "=secret", "a="+"x".repeat(4097), "a=b&".repeat(16)+"z=b"}) {
            var response=new MockHttpServletResponse();
            filter.doFilter(request(body),response,(a,b)->fail("malformed request reached handler"));
            assertEquals(400,response.getStatus(),body.substring(0,Math.min(30,body.length())));
            assertEquals("Identity form rejected",response.getContentAsString());
        }
        var query = request("address=a");query.setQueryString("address=b");
        var response = new MockHttpServletResponse();
        filter.doFilter(query,response,(a,b)->fail("query merged"));assertEquals(400,response.getStatus());
    }

    @Test void rejectsMultipartAndForeignCharsetsButLeavesUnrelatedRoutesAlone() throws Exception {
        for (String type : new String[]{"multipart/form-data;boundary=x", "application/json", "application/x-www-form-urlencoded;charset=ISO-8859-1", ""}) {
            var request=request("a=b");request.setContentType(type);var response=new MockHttpServletResponse();
            filter.doFilter(request,response,(a,b)->fail("unsupported media reached handler"));assertEquals(415,response.getStatus());
        }
        var get = request("");get.setMethod("GET");
        var other = request("");other.setServletPath("/product/upload");
        for (var request : new MockHttpServletRequest[]{get,other}) {
            var called = new java.util.concurrent.atomic.AtomicBoolean();
            filter.doFilter(request,new MockHttpServletResponse(),(a,b)->called.set(true));assertTrue(called.get());
        }
    }
}
