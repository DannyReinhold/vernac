// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package example.documentworkspace;

import jakarta.servlet.http.*;

import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.*;
import org.vernac.saas.mail.local.LocalMailInbox;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Only the explicit local profile exposes this capability-protected bounded in-memory inbox. */
@Configuration(proxyBeanMethods = false)
@Profile("local")
class LocalPreview {
    @Bean
    @Order(0)
    SecurityFilterChain localPreviewChain(HttpSecurity http, Environment env) throws Exception {
        String capability = env.getRequiredProperty("identity.preview-capability");
        if (!capability.matches("[A-Za-z0-9_-]{43}")
                || !env.getRequiredProperty("server.address").equals("127.0.0.1")
                || !env.getProperty("identity.allow-loopback-simulation", Boolean.class, false)
                || !env.getRequiredProperty("vernac.mail.provider")
                        .equals("org.vernac.saas.mail.local.NoopMailProvider"))
            throw new IllegalStateException("Local preview configuration unavailable");
        byte[] expected = ("Bearer " + capability).getBytes(StandardCharsets.US_ASCII);
        return http.securityMatcher(request -> request.getServletPath().equals("/__local/mail"))
                .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(
                        c ->
                                c.anyRequest()
                                        .access(
                                                (authentication, context) -> {
                                                    var request = context.getRequest();
                                                    String provided =
                                                            request.getHeader("Authorization");
                                                    boolean peer = false;
                                                    try {
                                                        peer =
                                                                InetAddress.getByName(
                                                                                request
                                                                                        .getRemoteAddr())
                                                                        .isLoopbackAddress();
                                                    } catch (Exception invalid) {
                                                    }
                                                    return new AuthorizationDecision(
                                                            request.isSecure()
                                                                    && request.getMethod()
                                                                            .equals("GET")
                                                                    && peer
                                                                    && provided != null
                                                                    && provided.length()
                                                                            == expected.length
                                                                    && MessageDigest.isEqual(
                                                                            expected,
                                                                            provided.getBytes(
                                                                                    StandardCharsets
                                                                                            .US_ASCII)));
                                                }))
                .exceptionHandling(
                        c ->
                                c.authenticationEntryPoint((r, s, e) -> s.setStatus(403))
                                        .accessDeniedHandler((r, s, e) -> s.setStatus(403)))
                .build();
    }

    @RestController
    @Profile("local")
    static class Inbox {
        private final LocalMailInbox inbox;

        Inbox(LocalMailInbox inbox) {
            this.inbox = inbox;
        }

        @GetMapping("/__local/mail")
        List<Map<String, String>> messages(HttpServletResponse response) {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Referrer-Policy", "no-referrer");
            response.setHeader(
                    "Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
            return inbox.previews().stream()
                    .map(
                            preview ->
                                    Map.of(
                                            "recipient",
                                            preview.message().recipient().encoded(),
                                            "text",
                                            preview.message().text().text()))
                    .toList();
        }
    }
}
