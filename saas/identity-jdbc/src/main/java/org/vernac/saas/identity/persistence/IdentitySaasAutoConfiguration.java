// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;



import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.session.jdbc.config.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.vernac.runtime.application.AdapterBindings;
import org.vernac.runtime.authorization.*;
import org.vernac.runtime.authorization.spring.VernacAuthorizationConfiguration;

import org.vernac.saas.identity.persistence.*;
import org.vernac.saas.identity.roles.RoleCatalog;
import org.vernac.saas.identity.security.*;
import org.vernac.saas.identity.security.credentials.*;
import org.vernac.saas.mail.domain.MailProvider;

import java.net.URI;
import java.time.Clock;
import java.util.*;

import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;

@org.springframework.boot.autoconfigure.AutoConfiguration(beforeName={"org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration", "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration"})
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="vernac.identity.enabled",havingValue="true")
@org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
@Import({IdentityJdbcSessions.class, VernacAuthorizationConfiguration.class})
public class IdentitySaasAutoConfiguration {
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(name="dataSource")
    @Primary
    DataSource dataSource(Environment e) {
        var pool = new com.zaxxer.hikari.HikariDataSource();
        pool.setJdbcUrl(e.getRequiredProperty("spring.datasource.url"));
        pool.setUsername(e.getRequiredProperty("spring.datasource.username"));
        pool.setPassword(e.getRequiredProperty("spring.datasource.password"));
        pool.setMaximumPoolSize(8);
        pool.setMinimumIdle(0);
        pool.setConnectionTimeout(2000);
        return pool;
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(name="transactionManager")
    @Primary
    PlatformTransactionManager transactionManager(@Qualifier("dataSource") DataSource dataSource) {
        return new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
    }


    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    Clock identityClock() {
        return Clock.systemUTC();
    }
    @Bean(destroyMethod = "close")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    BoundedCredentialPasswords credentialWork() {
        return new BoundedCredentialPasswords(
                new CredentialPasswords(OfflinePasswordBlocklist.bundled()));
    }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    @DependsOn(AdapterBindings.INITIALIZATION)
    IdentityJdbcAuthentication identityAuthentication(
            NamedParameterJdbcTemplate jdbc,
            @Qualifier("transactionManager") PlatformTransactionManager writer,
            Clock clock,
            BoundedCredentialPasswords passwords,
            Environment e) {
        return IdentityJdbcAuthentication.initializeDefaultPolicy(
                jdbc,
                writer,
                clock,
                e.getRequiredProperty("identity.admission-key-label"),
                new SecretKeySpec(key(e, "identity.admission-key"), "HmacSHA256"),
                passwords);
    }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    org.springframework.security.authentication.AuthenticationManager browserAuthenticationManager(
            IdentityJdbcAuthentication authentication) {
        return authentication.authenticationManager();
    }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    IdentityJourneys identityJourneys(
            NamedParameterJdbcTemplate jdbc,
            @Qualifier("transactionManager") PlatformTransactionManager writer,
            Clock clock,
            IdentityJdbcAuthentication authentication,
            IdentityAccounts accounts,
            BoundedCredentialPasswords passwords,
            MailProvider provider,
            Environment e) {
        String label = e.getRequiredProperty("identity.delivery-key-label");
        return new IdentityJourneys(
                jdbc,
                writer,
                clock,
                authentication,
                accounts,
                passwords,
                provider,
                URI.create(e.getRequiredProperty("identity.origin")),
                e.getProperty("identity.allow-loopback-simulation", Boolean.class, false),
                label,
                Map.of(label, key(e, "identity.delivery-key")));
    }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(name="identityMailWorker")
    SmartLifecycle identityMailWorker(IdentityJourneys journeys) {
        return journeys.mailLifecycle();
    }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    ActorResolver identityActors(IdentityJdbcAuthentication authentication) {
        return new SessionIdentityActorResolver(authentication.sessionTrust());
    }
    @Bean(destroyMethod = "")
    @SpringSessionDataSource
    @DependsOn(AdapterBindings.INITIALIZATION)
    DataSource identitySessionDataSource(@Qualifier("dataSource") DataSource dataSource) {
        return dataSource;
    }
    @Bean
    @SpringSessionTransactionManager
    PlatformTransactionManager identitySessionWriter(
            @Qualifier("transactionManager") PlatformTransactionManager writer) {
        return writer;
    }
    static byte[] key(Environment e, String name) {
        try {
            byte[] bytes = Base64.getDecoder().decode(e.getRequiredProperty(name));
            if (bytes.length != 32) throw new IllegalArgumentException();
            return bytes;
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Identity key configuration unavailable");
        }
    }

}
