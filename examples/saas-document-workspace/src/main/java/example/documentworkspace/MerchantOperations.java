// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package example.documentworkspace;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
import org.springframework.web.filter.OncePerRequestFilter;
import org.vernac.saas.identity.domain.AccountId;
import org.vernac.saas.identity.persistence.IdentityJdbcAuthentication;
import org.vernac.saas.identity.security.IdentityRequestAdmissionFilter;
import org.vernac.saas.payments.domain.PaymentOperationId;
import org.vernac.saas.payments.persistence.*;
import org.vernac.saas.payments.persistence.domain.*;
import org.vernac.saas.subscriptions.domain.MinorUnits;

/** Optional server-to-server merchant endpoint. Disabled unless explicitly configured.
 * No browser/session authority and no automatic refund policy. Put behind operator network controls.
 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="billing.operator.enabled",havingValue="true")
class MerchantOperations {
    static final String PATH="/__operator/refunds",STATUS="/__operator/billing-status";
    @Bean @Order(-2)
    SecurityFilterChain merchantChain(HttpSecurity http, Environment environment, IdentityJdbcAuthentication auth)throws Exception {
        String capability=environment.getRequiredProperty("billing.operator.capability");
        if(!capability.matches("[A-Za-z0-9_-]{43}"))throw new IllegalStateException("Invalid merchant capability configuration");
        byte[] expected=("Bearer "+capability).getBytes(StandardCharsets.US_ASCII);
        var gate=new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
                String provided=request.getHeader("Authorization");
                var headers=Collections.list(request.getHeaders("Authorization"));
                response.setHeader("Cache-Control","no-store");
                if(!request.isSecure() || !request.getMethod().equals("POST") || request.getQueryString()!=null
                        || headers.size()!=1 || provided==null || provided.length()!=expected.length
                        || !MessageDigest.isEqual(expected,provided.getBytes(StandardCharsets.US_ASCII))) {response.setStatus(403);return;}
                MerchantAccess.open(request);
                try {chain.doFilter(request,response);} finally {MerchantAccess.close(request);}
            }
        };
        return http.securityMatcher(r->r.getServletPath().equals(PATH)||r.getServletPath().equals(STATUS))
                .sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                .addFilterBefore(gate,SecurityContextHolderFilter.class)
                .addFilterBefore(new IdentityRequestAdmissionFilter(auth.requestAdmission()),SecurityContextHolderFilter.class)
                .authorizeHttpRequests(c->c.anyRequest().permitAll()).build();
    }
    @RestController
    @ConditionalOnProperty(name="billing.operator.enabled",havingValue="true")
    static final class OperatorController {
        private final PaymentFlow flow;
        private final org.vernac.saas.billing.lifecycle.BillingMaintenance maintenance;
        OperatorController(PaymentFlow flow,org.vernac.saas.billing.lifecycle.BillingMaintenance maintenance){this.flow=flow;this.maintenance=maintenance;}
        @PostMapping(STATUS)
        ResponseEntity<Map<org.vernac.saas.billing.lifecycle.BillingMaintenance.Stage,org.vernac.saas.billing.lifecycle.BillingMaintenance.Progress>> status() {
            MerchantAccess.requireAuthorized(null);
            return ResponseEntity.ok().header("Cache-Control","no-store").body(maintenance.progress());
        }
        @PostMapping(PATH)
        ResponseEntity<Map<String,String>> refund(HttpServletRequest request)throws IOException {
            try {
                MerchantAccess.requireAuthorized(null);
                if(request.getContentType()==null || !request.getContentType().equalsIgnoreCase("text/plain;charset=UTF-8")
                        || request.getHeader("Content-Encoding")!=null || request.getContentLengthLong()>1024)return response(400,Map.of("status","INVALID_INPUT"));
                byte[] bytes=request.getInputStream().readNBytes(1025);
                if(bytes.length>1024)return response(413,Map.of("status","INVALID_INPUT"));
                String body=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                // Four canonical lines: account, paid order, stable operation UUID, positive amount in cents.
                String[] lines=body.split("\\n",-1);
                if(lines.length!=4 || !lines[3].matches("[1-9][0-9]{0,14}"))return response(400,Map.of("status","INVALID_INPUT"));
                var result=flow.refund(AccountId.of(id(lines[0])),PaymentOrderId.of(id(lines[1])),PaymentOperationId.of(id(lines[2])),MinorUnits.of(Long.parseLong(lines[3])));
                return response(202,Map.of("refund",result.id().asString(),"status",result.state().name()));
            }catch(PaymentProcessingFailure failure) {return response(failure.code()==PaymentProcessingFailure.Code.UNAVAILABLE?503:409,Map.of("status","REVIEW_REQUIRED"));}
            catch(RuntimeException | CharacterCodingException invalid) {return response(400,Map.of("status","INVALID_INPUT"));}
        }
        private static UUID id(String text){var id=UUID.fromString(text);if(!id.toString().equals(text))throw new IllegalArgumentException();return id;}
        private static ResponseEntity<Map<String,String>> response(int status,Map<String,String> body){return ResponseEntity.status(status).header("Cache-Control","no-store").body(body);}
    }
}
