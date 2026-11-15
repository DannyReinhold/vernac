// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;
import java.io.IOException;
import java.util.Objects;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.vernac.saas.identity.domain.AccountId;
/** Synchronous browser product scope; only the server-stored AccountId is remembered, with fresh membership checks. */
public final class IdentityAccountSelectionFilter extends OncePerRequestFilter {
    public static final String SELECTED_ACCOUNT="org.vernac.saas.identity.selectedAccount";
    private final IdentityAccountActions accounts;
    public IdentityAccountSelectionFilter(IdentityAccountActions accounts){this.accounts=Objects.requireNonNull(accounts);}
    static boolean globalPath(String path){return path.equals("/identity/login") || path.equals("/identity/logout") || IdentityJourneyFilter.FORMS.contains(path) || IdentityAccountFilter.FORMS.contains(path) || IdentityAccountFilter.PAGES.contains(path);}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        if(globalPath(request.getServletPath())){chain.doFilter(request,response);return;}
        var session=request.getSession(false);Object selected=session==null?null:session.getAttribute(SELECTED_ACCOUNT);
        if(!(selected instanceof String value)){
            response.setHeader("Cache-Control","no-store");if(request.getMethod().equals("GET")){response.setStatus(303);response.setHeader("Location",request.getContextPath()+"/auth/accounts");}else response.setStatus(403);return;
        }
        try(var scope=accounts.select(IdentityAccountFilter.accountId(value))){chain.doFilter(request,response);}
        catch(IdentityAccountException denied){response.setHeader("Cache-Control","no-store");response.setStatus(denied.code()==IdentityAccountException.Code.ACCESS_DENIED || denied.code()==IdentityAccountException.Code.INVALID_INPUT?403:503);response.setContentType("text/plain;charset=UTF-8");response.getWriter().write("Account access unavailable. Choose an authorized account.");}
    }
}
