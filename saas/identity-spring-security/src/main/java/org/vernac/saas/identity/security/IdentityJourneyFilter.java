// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;
/** Explicit public forms. GET never calls an action; bearer fragments are scrubbed before explicit protected POST. */
public final class IdentityJourneyFilter extends OncePerRequestFilter {
    static final Set<String> PUBLIC=Set.of("/auth/register","/auth/recovery-request","/auth/verify","/auth/recover");
    static final Set<String> FORMS=Set.of("/auth/register","/auth/recovery-request","/auth/verify","/auth/recover","/auth/password","/auth/invitation");
    static final String SCRIPT="""
        (()=>{"use strict";const fragment=location.hash;history.replaceState(null,"",location.pathname);
        document.addEventListener("DOMContentLoaded",()=>{const token=document.getElementById("action-token");
        const button=document.getElementById("confirm-action");const value=fragment.slice(1);
        if(token&&button&&/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.[A-Za-z0-9_-]{43}$/.test(value)){
        token.value=value;button.disabled=false;}else{document.getElementById("action-message").textContent="Action unavailable. Request a new link.";}});})();
        """;
    private final IdentityBrowserActions actions;
    public IdentityJourneyFilter(IdentityBrowserActions actions){this.actions=Objects.requireNonNull(actions);}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String path=request.getServletPath();if(path.equals("/auth/invitation") && !(actions instanceof IdentityInvitationActions)){chain.doFilter(request,response);return;}if(!FORMS.contains(path) || !(request.getMethod().equals("GET") || request.getMethod().equals("POST"))){chain.doFilter(request,response);return;}
        response.setHeader("Cache-Control","no-store");response.setHeader("Referrer-Policy","no-referrer");response.setHeader("X-Content-Type-Options","nosniff");
        try {
            if(request.getMethod().equals("GET")){render(request,response,path);return;}
            if(!IdentityFormFilter.parsed(request))throw new IdentityActionException(IdentityActionException.Code.UNAVAILABLE);
            var source=IdentityLoginFilter.transportPeer(request.getRemoteAddr());
            boolean success=true;
            switch(path){
                case "/auth/register" -> actions.requestRegistration(request.getParameter("address"),source);
                case "/auth/recovery-request" -> actions.requestRecovery(request.getParameter("address"),source);
                case "/auth/verify" -> success=actions.verify(request.getParameter("token"),request.getParameter("password"),source);
                case "/auth/recover" -> success=actions.recover(request.getParameter("token"),request.getParameter("password"),source);
                case "/auth/invitation" -> success=((IdentityInvitationActions)actions).acceptInvitation(request.getParameter("token"),source);
                case "/auth/password" -> success=actions.changePassword(request.getParameter("currentPassword"),request.getParameter("password"),source);
                default -> throw new IllegalStateException();
            }
            if(path.equals("/auth/register") || path.equals("/auth/recovery-request")){
                response.setStatus(202);
                response.setContentType("text/html;charset=UTF-8");
                response.setHeader("Content-Security-Policy", "default-src 'none'; base-uri 'none'; frame-ancestors 'none'" + IdentityPageStyle.POLICY);
                response.getWriter().write("""
                        <!doctype html><html lang="en"><head><meta charset="utf-8">
                        <meta name="viewport" content="width=device-width,initial-scale=1">
                        <title>Check your inbox</title>%s</head><body><main>
                        <h1>Check your inbox</h1>
                        <p>If the address is eligible, follow the instructions sent to it.</p>
                        <details><summary>Using the local demo?</summary>
                        <p>The default local provider puts the message in a private preview. Leave the app running
                        and run this command from a second terminal in your project.</p>
                        <p>Windows<br><code>.\\mvnw.cmd exec:java "-Dexec.args=mail"</code></p>
                        <p>macOS or Linux<br><code>./mvnw exec:java -Dexec.args=mail</code></p>
                        <p>Open the link in the preview to choose your password.</p></details>
                        <p><a href="%s">Return to sign in</a></p></main></body></html>
                        """.formatted(IdentityPageStyle.ELEMENT, escape(request.getContextPath()+"/identity/login")));
            }else if(success && path.equals("/auth/invitation")){response.setStatus(303);response.setHeader("Location",request.getContextPath()+"/auth/accounts");
            }else if(success){
                SecurityContextHolder.clearContext();var session=request.getSession(false);if(session!=null)try{session.invalidate();}catch(IllegalStateException alreadyInvalid){}
                response.setStatus(303);response.setHeader("Location",request.getContextPath()+"/identity/login");
            }else reject(response,400,"Action unavailable. Request a new link or check your credentials.");
        }catch(IdentityActionException failure){
            int status=switch(failure.code()){case PASSWORD_POLICY,REJECTED->400;case ACCESS_DENIED->403;case THROTTLED->429;case AUTHENTICATION_REQUIRED->401;case UNAVAILABLE->503;};
            if(status==429)response.setHeader("Retry-After",Integer.toString(failure.retryAfterSeconds()));
            reject(response,status,failure.code()==IdentityActionException.Code.PASSWORD_POLICY?"Choose a password that meets the configured policy.":"Identity action unavailable");
        }catch(RuntimeException unavailable){reject(response,503,"Identity action unavailable");}
    }
    private void render(HttpServletRequest request,HttpServletResponse response,String path) throws IOException {
        if(request.getQueryString()!=null){reject(response,400,"Identity form unavailable");return;}
        Object attribute=request.getAttribute(CsrfToken.class.getName());if(!(attribute instanceof CsrfToken csrf))throw new IdentityActionException(IdentityActionException.Code.UNAVAILABLE);
        boolean invitation=path.equals("/auth/invitation");boolean bearer=invitation || path.equals("/auth/verify") || path.equals("/auth/recover");boolean address=path.equals("/auth/register") || path.equals("/auth/recovery-request");
        String title=switch(path){case "/auth/register"->"Create your account";case "/auth/recovery-request"->"Request password recovery";case "/auth/verify"->"Confirm your email and choose a password";case "/auth/recover"->"Reset your password";case "/auth/invitation"->"Accept workspace invitation";default->"Change your password";};
        response.setHeader("Content-Security-Policy","default-src 'none'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'"+IdentityPageStyle.POLICY+(bearer?"; script-src 'sha256-"+digest()+"'":""));
        String fields=invitation?"":address?"<p><label for=\"address\">Email address</label><br><input id=\"address\" name=\"address\" type=\"email\" autocomplete=\"username\" maxlength=\"320\" required></p>":
            (path.equals("/auth/password")?"<p><label for=\"current\">Current password</label><br><input id=\"current\" name=\"currentPassword\" type=\"password\" autocomplete=\"current-password\" maxlength=\"2048\" required></p>":"")+
            "<p><label for=\"password\">New password (15–128 characters)</label><br><input id=\"password\" name=\"password\" type=\"password\" autocomplete=\"new-password\" maxlength=\"2048\" required></p>";
        if(bearer)fields="<input type=\"hidden\" id=\"action-token\" name=\"token\" value=\"\">"+fields;
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().write("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>"+title+"</title>"+
            (bearer?"<script>"+SCRIPT+"</script>":"")+IdentityPageStyle.ELEMENT+"</head><body><main><h1>"+title+"</h1><p id=\"action-message\">"+
            (invitation?"Sign in with the intended verified address, then open this link again and explicitly accept. Opening the link alone changes nothing.":bearer?"Opening this page changes nothing. Choose your password and explicitly confirm.":"")+"</p><form method=\"post\" action=\""+escape(request.getContextPath()+path)+"\"><input type=\"hidden\" name=\""+escape(csrf.getParameterName())+"\" value=\""+escape(csrf.getToken())+"\">"+fields+
            "<button id=\"confirm-action\" type=\"submit\""+(bearer?" disabled":"")+">Continue</button></form><p><a href=\""+escape(request.getContextPath()+"/identity/login")+"\">Sign in</a></p></main></body></html>");
    }
    private static String digest(){try{return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(SCRIPT.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("Identity page unavailable");}}
    static String escape(String value){return value.replace("&","&amp;").replace("\"","&quot;").replace("'","&#39;").replace("<","&lt;").replace(">","&gt;");}
    private static void reject(HttpServletResponse response,int status,String message) throws IOException {response.resetBuffer();response.setStatus(status);response.setContentType("text/plain;charset=UTF-8");response.getWriter().write(message);}
}
