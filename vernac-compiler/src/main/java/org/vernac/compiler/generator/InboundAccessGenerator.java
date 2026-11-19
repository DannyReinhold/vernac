// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.generator;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import org.vernac.compiler.ast.InboundHttpAdapterNode;

final class InboundAccessGenerator {
    private static final ClassName SECURITY_CONTEXT_HOLDER =
            ClassName.get("org.springframework.security.core.context", "SecurityContextHolder");
    private static final ClassName TRUST_RESOLVER =
            ClassName.get("org.springframework.security.authentication", "AuthenticationTrustResolverImpl");
    private static final ClassName AUTHENTICATION_REQUIRED =
            ClassName.get("org.springframework.security.authentication", "InsufficientAuthenticationException");

    private InboundAccessGenerator() {}

    static void requireAuthentication(InboundHttpAdapterNode adapter, MethodSpec.Builder method) {
        if (adapter.access() != InboundHttpAdapterNode.Access.AUTHENTICATED) return;
        method.beginControlFlow("if (!new $T().isAuthenticated($T.getContext().getAuthentication()))",
                        TRUST_RESOLVER, SECURITY_CONTEXT_HOLDER)
                .addStatement("throw new $T($S)", AUTHENTICATION_REQUIRED, "Authentication required")
                .endControlFlow();
    }

    static void preserveDenial(InboundHttpAdapterNode adapter, MethodSpec.Builder handler) {
        if (adapter.access() != InboundHttpAdapterNode.Access.AUTHENTICATED) return;
        handler.beginControlFlow("if (error instanceof $T authenticationRequired)", AUTHENTICATION_REQUIRED)
                .addStatement("throw authenticationRequired")
                .endControlFlow();
    }
}
