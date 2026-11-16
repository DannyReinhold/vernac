// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;
import java.io.IOException;
import java.util.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;
import org.vernac.saas.identity.domain.*;
import org.vernac.saas.identity.roles.RoleAssignments;
import static org.vernac.saas.identity.security.IdentityJourneyFilter.escape;
/** Explicit scoped account screens/actions; installed after authorization with CSRF and bounded forms already applied. */
public final class IdentityAccountFilter extends OncePerRequestFilter {
    static final Set<String> PAGES=Set.of("/auth/accounts","/auth/account");
    static final Set<String> FORMS=Set.of("/auth/account/select","/auth/account/create","/auth/account/roles","/auth/account/remove","/auth/account/transfer","/auth/account/close","/auth/account/invite","/auth/account/revoke","/auth/identity/disable","/auth/identity/close");
    private final IdentityAccountActions accounts;private final IdentityInvitationActions invitations;private final IdentityLocalReturnTarget returns;
    public IdentityAccountFilter(IdentityAccountActions accounts,IdentityInvitationActions invitations,IdentityLocalReturnTarget returns){this.accounts=Objects.requireNonNull(accounts);this.invitations=Objects.requireNonNull(invitations);this.returns=Objects.requireNonNull(returns);}
    static UUID identifier(String text){try{if(text==null || !text.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IllegalArgumentException();return UUID.fromString(text);}catch(RuntimeException invalid){throw new IdentityAccountException(IdentityAccountException.Code.INVALID_INPUT);}}
    static AccountId accountId(String text){return AccountId.of(identifier(text));}
    private static Collection<String> roles(String text){try{if(text==null)throw new IllegalArgumentException();var values=text.isBlank()?List.<String>of():Arrays.stream(text.split(",",-1)).map(String::strip).toList();return RoleAssignments.decode(RoleAssignments.encode(values));}catch(RuntimeException invalid){throw new IdentityAccountException(IdentityAccountException.Code.INVALID_ROLES);}}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        String path=request.getServletPath();if(!(request.getMethod().equals("GET")&&PAGES.contains(path)) && !(request.getMethod().equals("POST")&&FORMS.contains(path))){chain.doFilter(request,response);return;}
        response.setHeader("Cache-Control","no-store");response.setHeader("Referrer-Policy","no-referrer");response.setHeader("Content-Security-Policy","default-src 'none'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'");response.setHeader("X-Content-Type-Options","nosniff");
        try{
            if(request.getMethod().equals("GET")){render(request,response,path);return;}
            if(!IdentityFormFilter.parsed(request))throw new IdentityAccountException(IdentityAccountException.Code.CONFIGURATION);
            switch(path){
                case "/auth/account/select"->{var id=accountId(request.getParameter("account"));try(var scope=accounts.select(id)){request.getSession().setAttribute(IdentityAccountSelectionFilter.SELECTED_ACCOUNT,id.asString());}response.setStatus(303);response.setHeader("Location",returns.resolve(request.getParameter("returnTo")));return;}
                case "/auth/account/create"->accounts.createOrganization();
                case "/auth/account/roles"->accounts.assignRoles(accountId(request.getParameter("account")),UserId.of(identifier(request.getParameter("subject"))),roles(request.getParameter("roles")));
                case "/auth/account/remove"->accounts.removeMember(accountId(request.getParameter("account")),UserId.of(identifier(request.getParameter("subject"))));
                case "/auth/account/transfer"->accounts.transferOwnership(accountId(request.getParameter("account")),UserId.of(identifier(request.getParameter("subject"))));
                case "/auth/account/close"->{var id=accountId(request.getParameter("account"));accounts.closeAccount(id);var session=request.getSession(false);if(session!=null && id.asString().equals(session.getAttribute(IdentityAccountSelectionFilter.SELECTED_ACCOUNT)))session.removeAttribute(IdentityAccountSelectionFilter.SELECTED_ACCOUNT);}
                case "/auth/account/invite"->{invitations.requestInvitation(accountId(request.getParameter("account")),request.getParameter("address"),roles(request.getParameter("roles")),IdentityLoginFilter.transportPeer(request.getRemoteAddr()));response.setStatus(202);response.setContentType("text/plain;charset=UTF-8");response.getWriter().write("If the address is eligible, follow the instructions sent to it.");return;}
                case "/auth/account/revoke"->accounts.revokeInvitation(accountId(request.getParameter("account")),InvitationId.of(identifier(request.getParameter("invitation"))));
                case "/auth/identity/disable"->{accounts.disableIdentity();logout(request);response.setStatus(303);response.setHeader("Location",request.getContextPath()+"/identity/login");return;}
                case "/auth/identity/close"->{accounts.closeIdentity();logout(request);response.setStatus(303);response.setHeader("Location",request.getContextPath()+"/identity/login");return;}
                default->throw new IdentityAccountException(IdentityAccountException.Code.INVALID_INPUT);
            }
            response.setStatus(303);response.setHeader("Location",request.getContextPath()+"/auth/accounts");
        }catch(IdentityAccountException failure){int status=switch(failure.code()){case ACCESS_DENIED->403;case LAST_OWNER,BILLING_ACTIVE,CAPACITY->409;case INVALID_INPUT,INVALID_ROLES->400;case CONFIGURATION,UNAVAILABLE->503;};
            reject(response,status,failure.code()==IdentityAccountException.Code.LAST_OWNER?"Transfer ownership or close the account before removing its last active owner.":failure.code()==IdentityAccountException.Code.BILLING_ACTIVE?"Cancel renewal, wait until the paid period ends and resolve pending payments or refunds before closing this account.":"Account operation unavailable");}
        catch(IdentityActionException failure){int status=switch(failure.code()){case ACCESS_DENIED->403;case PASSWORD_POLICY,REJECTED->400;case AUTHENTICATION_REQUIRED->401;case THROTTLED->429;case UNAVAILABLE->503;};if(status==429)response.setHeader("Retry-After",Integer.toString(failure.retryAfterSeconds()));reject(response,status,"Account operation unavailable");}
        catch(RuntimeException unavailable){reject(response,503,"Account operation unavailable");}
    }
    private static void logout(HttpServletRequest request){SecurityContextHolder.clearContext();var session=request.getSession(false);if(session!=null)try{session.invalidate();}catch(IllegalStateException alreadyInvalid){}}
    private static void reject(HttpServletResponse response,int status,String message)throws IOException{response.resetBuffer();response.setStatus(status);response.setContentType("text/plain;charset=UTF-8");response.getWriter().write(message);}
    private void render(HttpServletRequest request,HttpServletResponse response,String path)throws IOException {
        Object attribute=request.getAttribute(CsrfToken.class.getName());if(!(attribute instanceof CsrfToken csrf))throw new IdentityAccountException(IdentityAccountException.Code.CONFIGURATION);
        StringBuilder html=new StringBuilder("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Workspaces</title></head><body><main>");
        if(path.equals("/auth/accounts")){
            html.append("<h1>Your workspaces</h1><ul>");for(var account:accounts.accounts()){
                String label=account.kind().equals("PERSONAL")?"Personal workspace":"Workspace "+account.account().asString().substring(0,8);
                html.append("<li>").append(escape(label)).append(form(request,csrf,"/auth/account/select",Map.of("account",account.account().asString()),"Open workspace",""));
                if(account.owner())html.append("<a href=\"").append(escape(request.getContextPath()+"/auth/account?account="+account.account().asString())).append("\">Manage members</a>");html.append("</li>");
            }html.append("</ul>").append(form(request,csrf,"/auth/account/create",Map.of(),"Create workspace",""));
            html.append("<p><a href=\"").append(escape(request.getContextPath()+"/auth/password")).append("\">Change password</a></p>");
            html.append(form(request,csrf,"/identity/logout",Map.of(),"Sign out","")).append("<h2>End access</h2><p>Transfer ownership or close owned workspaces first.</p>")
                .append(form(request,csrf,"/auth/identity/disable",Map.of(),"Disable my identity","")).append(form(request,csrf,"/auth/identity/close",Map.of(),"Close my identity",""));
        }else{
            var account=accountId(request.getParameter("account"));var members=accounts.members(account);var pending=accounts.invitations(account);var available=accounts.availableRoles();
            html.append("<h1>Manage workspace members</h1><p>Available product roles: ").append(escape(String.join(", ",available))).append("</p><p>Separate multiple roles with commas. Product roles do not grant ownership.</p><table><thead><tr><th>Member</th><th>Ownership</th><th>Roles and actions</th></tr></thead><tbody>");
            for(var member:members){html.append("<tr><td>").append(escape(member.address().isEmpty()?"Member":member.address())).append(member.active()?"":" (removed)").append("</td><td>").append(member.owner()?"Owner":"Member").append("</td><td>");
                if(member.active()){var ids=Map.of("account",account.asString(),"subject",member.subject().asString());
                    html.append(form(request,csrf,"/auth/account/roles",ids,"Update roles","<label>Product roles <input name=\"roles\" value=\""+escape(String.join(",",new TreeSet<>(member.roles())))+"\" maxlength=\"8224\"></label>"))
                        .append(form(request,csrf,"/auth/account/remove",ids,"Remove member",""));
                    if(!member.owner())html.append(form(request,csrf,"/auth/account/transfer",ids,"Transfer my ownership to this member",""));}
                html.append("</td></tr>");
            }html.append("</tbody></table><h2>Invite a member</h2>").append(form(request,csrf,"/auth/account/invite",Map.of("account",account.asString()),"Send invitation","<p><label>Email address <input name=\"address\" type=\"email\" autocomplete=\"email\" maxlength=\"320\" required></label></p><p><label>Product roles <input name=\"roles\" maxlength=\"8224\"></label></p>"));
            html.append("<h2>Pending invitations</h2><ul>");for(var invitation:pending)html.append("<li>").append(escape(invitation.recipient())).append(" · ").append(escape(invitation.expires().toString())).append(form(request,csrf,"/auth/account/revoke",Map.of("account",account.asString(),"invitation",invitation.invitation().asString()),"Revoke invitation","")).append("</li>");
            html.append("</ul><h2>Close workspace</h2>").append(form(request,csrf,"/auth/account/close",Map.of("account",account.asString()),"Close this workspace",""))
                .append("<p><a href=\"").append(escape(request.getContextPath()+"/auth/accounts")).append("\">Your workspaces</a></p>");
        }
        html.append("</main></body></html>");response.setContentType("text/html;charset=UTF-8");response.getWriter().write(html.toString());
    }
    private static String form(HttpServletRequest request,CsrfToken csrf,String path,Map<String,String> values,String button,String controls){
        var html=new StringBuilder("<form method=\"post\" action=\"").append(escape(request.getContextPath()+path)).append("\"><input type=\"hidden\" name=\"").append(escape(csrf.getParameterName())).append("\" value=\"").append(escape(csrf.getToken())).append("\">");
        values.forEach((key,value)->html.append("<input type=\"hidden\" name=\"").append(escape(key)).append("\" value=\"").append(escape(value)).append("\">"));
        return html.append(controls).append("<button type=\"submit\">").append(escape(button)).append("</button></form>").toString();
    }
}
