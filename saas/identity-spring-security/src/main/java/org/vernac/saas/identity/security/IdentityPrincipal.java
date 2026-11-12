// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;

import java.io.*;
import java.security.Principal;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.vernac.saas.identity.domain.UserId;

/** Technical session DTO: a nominal human subject, never a grant or cached enabled state. */
@NullMarked
public final class IdentityPrincipal implements Principal, Serializable {
    @Serial private static final long serialVersionUID=1L;
    // Keep generated domain types free of a Serializable requirement.
    private final UUID subject;
    public IdentityPrincipal(UserId userId) {subject=Objects.requireNonNull(userId).value();}
    public UserId userId() {return UserId.of(subject);}
    /** Stable session-index subject; security-sensitive metadata, not intended for logging. */
    @Override public String getName() {return subject.toString();}
    @Override public String toString() {return "IdentityPrincipal[redacted]";}
    @Override public boolean equals(@Nullable Object other) {return other instanceof IdentityPrincipal p && subject.equals(p.subject);}
    @Override public int hashCode() {return subject.hashCode();}
    @Serial private void readObject(ObjectInputStream input) throws IOException,ClassNotFoundException {
        input.defaultReadObject();
        if(subject==null)throw new InvalidObjectException("Invalid identity principal");
    }
}
