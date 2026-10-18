package org.example.tasks.adapter.inbound.web;

import org.jspecify.annotations.NullMarked;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * An intentionally public starter. Add authentication and tenant policies before sharing real data.
 */
@Configuration(proxyBeanMethods = false)
@NullMarked
public class WebSecurity {
    @Bean
    SecurityFilterChain web(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(
                        requests ->
                                requests.requestMatchers("/actuator/health", "/actuator/health/**")
                                        .permitAll()
                                        .requestMatchers("/actuator/**")
                                        .denyAll()
                                        .anyRequest()
                                        .permitAll())
                .headers(
                        headers ->
                                headers.contentSecurityPolicy(
                                        policy ->
                                                policy.policyDirectives(
                                                        "default-src 'self'; style-src 'self';"
                                                            + " img-src 'self' data:; script-src"
                                                            + " 'none'; object-src 'none'; base-uri"
                                                            + " 'self'; frame-ancestors 'none'")))
                // CSRF protection remains enabled. Thymeleaf POST forms include its token.
                .build();
    }
}
