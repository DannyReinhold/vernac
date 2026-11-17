// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package example.documentworkspace;

import example.documentworkspace.authorization.WorkspacePermissions;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.PlatformTransactionManager;
import org.vernac.runtime.authorization.*;
import org.vernac.saas.billing.web.*;
import org.vernac.saas.identity.persistence.*;
import org.vernac.saas.identity.roles.RoleCatalog;
import org.vernac.saas.identity.security.*;

@Configuration(proxyBeanMethods = false)
public class IdentitySetup {
    @Bean
    RoleCatalog roleCatalog() {
        return RoleCatalog.fromClasspath(
                getClass().getClassLoader(), "META-INF/vernac/roles.json", List.of(WorkspacePermissions.values()));
    }
    @Bean
    IdentityAccounts identityAccounts(
            NamedParameterJdbcTemplate jdbc,
            @Qualifier("transactionManager") PlatformTransactionManager writer,
            Clock clock,
            IdentityJdbcAuthentication authentication,
            RoleCatalog catalog,
            example.documentworkspace.domain.WorkspaceMembership membership,
            org.vernac.saas.billing.lifecycle.BillingAccountLifecycle billingLifecycle) {
        // This demonstration has one workspace-wide operation and no resource-specific
        // authorization.
        PermissionEvaluator objects =
                request ->
                        List.of(WorkspacePermissions.values()).stream()
                                                .anyMatch(
                                                        p ->
                                                                p.qualifiedName()
                                                                        .equals(
                                                                                request.permission()
                                                                                        .qualifiedName()))
                                        && request.resource() == null
                                ? PermissionDecision.ALLOW
                                : PermissionDecision.DENY;
        return new IdentityAccounts(
                jdbc,
                writer,
                clock,
                authentication.sessionTrust(),
                catalog,
                List.of(membership.initialRole()),
                objects,
                billingLifecycle);
    }
    @Bean
    SecurityFilterChain identityBrowser(
            HttpSecurity http,
            IdentityJdbcAuthentication authentication,
            IdentityJourneys journeys,
            SubscriptionAccounts accounts)
            throws Exception {
        return BillingWebSecurity.build(
                http,
                authentication.authenticationManager(),
                authentication.sessionTrust(),
                new IdentityLocalReturnTarget("/workspace"),
                authentication.requestAdmission(),
                journeys,
                accounts,
                Set.of("/documents/create", "/documents/delete", "/documents/export"));
    }
}
