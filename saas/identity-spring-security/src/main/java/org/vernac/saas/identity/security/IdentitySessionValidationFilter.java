// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;
import java.io.IOException;
import java.util.Objects;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
/** Explicitly install after SecurityContextHolderFilter and before protected request handlers. */
public final class IdentitySessionValidationFilter extends OncePerRequestFilter {
 private final IdentitySessionTrust trust;
 public IdentitySessionValidationFilter(IdentitySessionTrust trust){this.trust=Objects.requireNonNull(trust);}
 @Override protected boolean shouldNotFilterAsyncDispatch(){return false;}
 @Override protected boolean shouldNotFilterErrorDispatch(){return false;}
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
  throws ServletException,IOException {
  var authentication=SecurityContextHolder.getContext().getAuthentication();
  if(authentication==null || !(authentication.getPrincipal() instanceof IdentityPrincipal)){chain.doFilter(request,response);return;}
  final boolean current;
  try{current=trust.current(authentication);}
  catch(RuntimeException unavailable){
   // Preserve the persisted session on a transient lookup failure; deny this dispatch.
   SecurityContextHolder.clearContext();
   response.setHeader("Cache-Control","no-store");response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
   response.setContentType("text/plain;charset=UTF-8");response.getWriter().write("Identity authentication unavailable");return;
  }
  if(!current){
   SecurityContextHolder.clearContext();
   HttpSession session=request.getSession(false);
   if(session!=null)try{session.invalidate();}catch(IllegalStateException alreadyInvalid){}
   response.setHeader("Cache-Control","no-store");
  }
  chain.doFilter(request,response);
 }
 @Override public String toString(){return "IdentitySessionValidationFilter[REDACTED]";}
}
