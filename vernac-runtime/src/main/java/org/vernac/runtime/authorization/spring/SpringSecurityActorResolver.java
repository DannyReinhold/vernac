// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.authorization.spring;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.NullMarked;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.vernac.runtime.authorization.ActorResolver;
import org.vernac.runtime.authorization.VerifiedActor;

/** Explicit trusted mapper; no authority, role or principal-name inference into a grant. */
@NullMarked
public final class SpringSecurityActorResolver implements ActorResolver {
    private final Function<Authentication, Optional<VerifiedActor>> mapper;
    public SpringSecurityActorResolver(Function<Authentication, Optional<VerifiedActor>> mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }
    @Override public Optional<VerifiedActor> resolve() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) return Optional.empty();
        if (auth.getPrincipal() instanceof UserDetails user && (!user.isEnabled() || !user.isAccountNonLocked()
                || !user.isAccountNonExpired() || !user.isCredentialsNonExpired())) return Optional.empty();
        // Application mapping must verify supported principal/issuer, enabled state and technical actor kind.
        return Objects.requireNonNull(mapper.apply(auth));
    }
}
