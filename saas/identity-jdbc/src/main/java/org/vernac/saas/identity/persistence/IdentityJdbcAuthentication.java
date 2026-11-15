// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import java.time.Clock;
import java.util.Objects;
import javax.crypto.SecretKey;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.vernac.runtime.authorization.AuthorizationGuard;
import org.vernac.runtime.tenancy.TenantContext;
import org.vernac.saas.identity.security.IdentitySessionTrust;
import org.vernac.saas.identity.security.IdentityRequestAdmission;
import org.vernac.saas.identity.security.credentials.BoundedCredentialPasswords;

/** Explicit application assembly; no repository exports, configuration scanning or implicit key generation. */
public final class IdentityJdbcAuthentication {
    private final AuthenticationManager manager;
    private final IdentitySessionTrust sessions;
    private final IdentityRequestAdmission ingress;
    private final JdbcIdentityAdmission keyedAdmission;
    private IdentityJdbcAuthentication(AuthenticationManager manager,IdentitySessionTrust sessions,IdentityRequestAdmission ingress,JdbcIdentityAdmission keyedAdmission) {
        this.manager=manager;this.sessions=sessions;this.ingress=ingress;this.keyedAdmission=keyedAdmission;
    }

    /** Independently initializes/checks the published default capacity and admission policy at startup. */
    public static IdentityJdbcAuthentication initializeDefaultPolicy(NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager transactions,Clock clock,String admissionKeyLabel,SecretKey admissionKey,
            BoundedCredentialPasswords passwords) {
        try {
            if(TransactionSynchronizationManager.isActualTransactionActive()
                    || TransactionSynchronizationManager.isSynchronizationActive()
                    || !TransactionSynchronizationManager.getResourceMap().isEmpty()
                    || TenantContext.currentTenant().isPresent() || AuthorizationGuard.currentActor().isPresent())
                throw new IllegalStateException();
            Objects.requireNonNull(jdbc);Objects.requireNonNull(clock);Objects.requireNonNull(passwords);
            if(!(transactions instanceof DataSourceTransactionManager writer)
                    || writer.getDataSource()!=jdbc.getJdbcTemplate().getDataSource()) throw new IllegalStateException();
            // Validate key and complete fixed policy before any persistent initialization.
            var admission=new JdbcIdentityAdmission(jdbc,transactions,clock,admissionKeyLabel,admissionKey,JdbcIdentityAdmission.defaults());
            var bootstrap=new TransactionTemplate(transactions);
            bootstrap.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            bootstrap.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);bootstrap.setTimeout(5);
            bootstrap.executeWithoutResult(status->new JdbcIdentityCapacity(jdbc).initialize(JdbcIdentityCapacity.defaults()));
            var global=new JdbcIdentityIngress(jdbc,transactions,clock);
            global.initialize(JdbcIdentityIngress.DEFAULT_LIMIT);
            admission.initialize();
            return new IdentityJdbcAuthentication(new ProviderManager(new JdbcIdentityAuthenticationProvider(
                    jdbc,transactions,admission,passwords,clock)),new IdentitySessionTrust(new JdbcIdentitySessionEpochs(jdbc,transactions),clock),()->{
                        var decision=global.tryAcquire();return new IdentityRequestAdmission.Decision(decision.admitted(),decision.retryAfterSeconds());
                    },admission);
        } catch(RuntimeException unavailable) {
            throw new IllegalStateException("Identity JDBC authentication configuration unavailable");
        }
    }

    /** One bounded persisted-cursor sweep; invoke from an explicitly configured background scheduler. */
    public void maintainAdmission() {
        try { keyedAdmission.cleanup(250); }
        catch(RuntimeException unavailable) { throw new IllegalStateException("Identity admission maintenance unavailable"); }
    }
    public IdentityRequestAdmission requestAdmission() { return ingress; }
    public AuthenticationManager authenticationManager() { return manager; }
    public IdentitySessionTrust sessionTrust() { return sessions; }
    @Override public String toString() { return "IdentityJdbcAuthentication[REDACTED]"; }
}
