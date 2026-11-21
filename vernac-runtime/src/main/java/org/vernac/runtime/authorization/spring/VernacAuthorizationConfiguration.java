// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.authorization.spring;

import org.springframework.beans.factory.ObjectProvider;
import org.jspecify.annotations.NullMarked;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.vernac.runtime.authorization.*;

/** Explicit @Import. No auto-allow provider, security filter chain or method-security interceptor. */
@NullMarked
@Configuration(proxyBeanMethods = false)
public class VernacAuthorizationConfiguration {
    @Bean public AuthorizationGuard vernacAuthorizationGuard(ObjectProvider<ActorResolver> actorBeans, ObjectProvider<PermissionEvaluator> policyBeans) {
        var actors = actorBeans.stream().toList();
        var evaluators = policyBeans.stream().toList();
        if (actors.size() != 1 || evaluators.size() != 1)
            throw new AuthorizationException(AuthorizationException.Code.AUTHORIZATION_CONFIGURATION_ERROR);
        return new AuthorizationGuard(actors.getFirst(), evaluators.getFirst());
    }
}
