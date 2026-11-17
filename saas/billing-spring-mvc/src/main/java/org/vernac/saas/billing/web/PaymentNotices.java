// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.billing.web;

import jakarta.servlet.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.bind.annotation.*;
import org.vernac.saas.identity.persistence.IdentityJdbcAuthentication;
import org.vernac.saas.identity.security.IdentityRequestAdmissionFilter;
import org.vernac.saas.payments.PaymentFailure;
import org.vernac.saas.payments.domain.*;
import org.vernac.saas.payments.persistence.*;
import org.vernac.saas.payments.persistence.domain.PaymentProfileKey;

/** One configured provider profile per endpoint. Callback data never selects an account or tenant. */
@Configuration(proxyBeanMethods = false)
@Import(PaymentNotices.Notice.class)
class PaymentNotices {
    static final String PATH = "/billing/provider-notice";
    @Bean @Order(-1)
    SecurityFilterChain paymentNoticeChain(HttpSecurity http, IdentityJdbcAuthentication authentication) throws Exception {
        return http.securityMatcher(request -> request.getServletPath().equals(PATH))
                .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
                // This exact machine endpoint has no browser/session authority. All billing forms retain CSRF.
                .csrf(AbstractHttpConfigurer::disable)
                .addFilterBefore(new IdentityRequestAdmissionFilter(authentication.requestAdmission()), SecurityContextHolderFilter.class)
                .authorizeHttpRequests(c -> c.anyRequest().permitAll()).build();
    }
    @RestController
    static class Notice {
        private final PaymentFlow payments;
        private final PaymentProvider provider;
        private final PaymentProfileKey profile;
        Notice(PaymentFlow payments, PaymentProvider provider, Environment environment) {
            this.payments=payments;this.provider=provider;
            profile=PaymentProfileKey.of(environment.getRequiredProperty("billing.profile"));
        }
        @PostMapping(PATH)
        ResponseEntity<Void> receive(HttpServletRequest request) {
            int status=400;
            try {
                String expected=provider.kind()==PaymentProviderKind.STRIPE ? "application/json" : "application/x-www-form-urlencoded";
                String content=request.getContentType();
                if (!request.isSecure() || request.getQueryString()!=null || request.getHeader("Content-Encoding")!=null
                        || content==null || !content.split(";",2)[0].trim().equalsIgnoreCase(expected)
                        ) return response(400);
                if(request.getContentLengthLong()>65536)return response(413);
                byte[] bytes=request.getInputStream().readNBytes(65537);
                if(bytes.length>65536)return response(413);
                String body=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                var signatures=Collections.list(request.getHeaders("Stripe-Signature"));
                if(signatures.size()>1 || signatures.stream().anyMatch(s -> s.length()>8192))return response(400);
                if(provider.kind()!=PaymentProviderKind.STRIPE && !signatures.isEmpty())return response(400);
                payments.webhook(profile,PaymentNoticeBody.of(body),PaymentSignature.of(signatures.isEmpty()?"":signatures.getFirst()));
                return response(204);
            } catch(PaymentProcessingFailure failure) {
                status=failure.code()==PaymentProcessingFailure.Code.UNAVAILABLE ? 503 : 400;
            } catch(PaymentFailure failure) {
                status=Set.of(PaymentFailure.Code.UNAVAILABLE,PaymentFailure.Code.OUTCOME_UNKNOWN).contains(failure.code()) ? 503 : 400;
            } catch(Exception invalid) { /* No untrusted body, signature or provider payload in a response/log. */ }
            return response(status);
        }
        private static ResponseEntity<Void> response(int status) {
            return ResponseEntity.status(status).header("Cache-Control","no-store").build();
        }
    }
}
