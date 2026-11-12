// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;

import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.vernac.runtime.authorization.*;
import org.vernac.runtime.authorization.spring.SpringSecurityActorResolver;
import org.vernac.saas.identity.domain.IdentityDirectory;
import org.vernac.saas.identity.domain.IdentityStatus;

/** Explicit human-principal adapter; no automatic bean/filter registration or tenant selection. */
@NullMarked
public final class IdentityActorResolver implements ActorResolver {
    public static final String REALM="vernac.saas.identity";
    private final SpringSecurityActorResolver delegate;
    private final ThreadLocal<Boolean> resolving=new ThreadLocal<>();
    public IdentityActorResolver(IdentityDirectory identities) {
        Objects.requireNonNull(identities);
        delegate=new SpringSecurityActorResolver(authentication->{
            if(!(authentication.getPrincipal() instanceof IdentityPrincipal principal))return Optional.empty();
            Optional<IdentityStatus> status;
            try {status=identities.status(principal.userId());}
            catch(RuntimeException unavailable) {
                if(unavailable instanceof AuthorizationException safe && (safe.code()==AuthorizationException.Code.AUTHORIZATION_CONFIGURATION_ERROR
                        || safe.code()==AuthorizationException.Code.AUTHORIZATION_UNAVAILABLE))throw safe;
                // The lookup may carry JDBC/provider credentials or input values in its exception.
                throw new AuthorizationException(AuthorizationException.Code.AUTHORIZATION_UNAVAILABLE);
            }
            if(status==null)throw new AuthorizationException(AuthorizationException.Code.AUTHORIZATION_CONFIGURATION_ERROR);
            if(status.orElse(null)!=IdentityStatus.ACTIVE)return Optional.empty();
            return Optional.of(new VerifiedActor(REALM,principal.userId().asString(),VerifiedActor.Kind.HUMAN));
        });
    }
    @Override public Optional<VerifiedActor> resolve() {
        if(Boolean.TRUE.equals(resolving.get()))throw new AuthorizationException(AuthorizationException.Code.AUTHORIZATION_CONFIGURATION_ERROR);
        resolving.set(true);
        try{return delegate.resolve();}
        finally{resolving.remove();}
    }
}
