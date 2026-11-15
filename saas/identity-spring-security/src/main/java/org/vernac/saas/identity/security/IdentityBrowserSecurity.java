// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;

import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.session.*;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.*;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.util.matcher.RequestMatcher;

/** Opt-in, HTTPS-only browser chain. The application explicitly supplies its manager and fresh Identity metadata. */
public final class IdentityBrowserSecurity {
    private IdentityBrowserSecurity() {}

    public static SecurityFilterChain build(HttpSecurity http, AuthenticationManager manager,
            IdentitySessionTrust trust, IdentityLocalReturnTarget returns, IdentityRequestAdmission admission) throws Exception {
        return assemble(http,manager,trust,returns,admission,null);
    }
    public static SecurityFilterChain build(HttpSecurity http,AuthenticationManager manager,IdentitySessionTrust trust,
            IdentityLocalReturnTarget returns,IdentityRequestAdmission admission,IdentityBrowserActions actions) throws Exception {
        return assemble(http,manager,trust,returns,admission,Objects.requireNonNull(actions));
    }
    private static SecurityFilterChain assemble(HttpSecurity http,AuthenticationManager manager,IdentitySessionTrust trust,
            IdentityLocalReturnTarget returns,IdentityRequestAdmission admission,IdentityBrowserActions actions) throws Exception {
        Objects.requireNonNull(http);Objects.requireNonNull(manager);Objects.requireNonNull(trust);Objects.requireNonNull(returns);Objects.requireNonNull(admission);
        var contexts = new HttpSessionSecurityContextRepository();
        contexts.setDisableUrlRewriting(true);
        var csrf = new HttpSessionCsrfTokenRepository();
        var sessions = new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(csrf)));
        http.authenticationManager(manager)
                .securityContext(c -> c.requireExplicitSave(true).securityContextRepository(contexts))
                .requestCache(c -> c.requestCache(new NullRequestCache()))
                .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .enableSessionUrlRewriting(false).sessionAuthenticationStrategy(sessions))
                .csrf(c -> c.csrfTokenRepository(csrf))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(c -> c.logoutRequestMatcher(path("POST", "/identity/logout"))
                        .invalidateHttpSession(true).clearAuthentication(true)
                        .logoutSuccessHandler((request,response,authentication) -> {
                            response.setHeader("Cache-Control", "no-store");response.setStatus(204);
                        }))
                .exceptionHandling(c -> c.authenticationEntryPoint((request,response,failure) -> {
                    response.setHeader("Cache-Control", "no-store");response.setStatus(401);
                }).accessDeniedHandler((request,response,failure) -> {
                    response.setHeader("Cache-Control", "no-store");response.setStatus(403);
                }))
                .authorizeHttpRequests(c -> c.requestMatchers(path("GET", "/identity/login"),path("POST", "/identity/login"),request -> actions!=null && (IdentityJourneyFilter.PUBLIC.contains(request.getServletPath()) && Set.of("GET","POST").contains(request.getMethod()) || actions instanceof IdentityInvitationActions && request.getServletPath().equals("/auth/invitation") && request.getMethod().equals("GET"))).permitAll()
                        .anyRequest().access((authentication, context) -> {
                            var value=authentication.get();
                            return new AuthorizationDecision(value != null && value.isAuthenticated()
                                    && value.getPrincipal() instanceof IdentityPrincipal);
                        }));
        http.addFilterBefore(new IdentitySecureTransportFilter(), SecurityContextHolderFilter.class);
        http.addFilterAfter(new IdentityRequestAdmissionFilter(admission), IdentitySecureTransportFilter.class);
        http.addFilterAfter(new IdentitySessionValidationFilter(trust), SecurityContextHolderFilter.class);
        var forms=new HashSet<>(Set.of("/identity/login","/identity/logout"));
        if(actions!=null){forms.addAll(IdentityJourneyFilter.FORMS);http.addFilterAfter(new IdentityJourneyFilter(actions),org.springframework.security.web.access.intercept.AuthorizationFilter.class);}
        http.addFilterBefore(new IdentityFormFilter(forms), CsrfFilter.class);
        http.addFilterAfter(new IdentityLoginPageFilter(returns,actions!=null), CsrfFilter.class);
        http.addFilterAt(new IdentityLoginFilter("/identity/login",manager,contexts,sessions,returns), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static RequestMatcher path(String method,String path) {
        return request -> method.equals(request.getMethod()) && path.equals(request.getServletPath());
    }
}
