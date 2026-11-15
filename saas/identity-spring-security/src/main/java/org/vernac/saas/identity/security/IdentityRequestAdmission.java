// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;

/** Trusted server-side shared ingress gate, called before session/parameter lookup and outside business contexts. */
@FunctionalInterface
public interface IdentityRequestAdmission {
    Decision tryAcquire();
    record Decision(boolean admitted,int retryAfterSeconds) {
        public Decision {
            if(admitted ? retryAfterSeconds!=0 : retryAfterSeconds<1 || retryAfterSeconds>60)
                throw new IllegalArgumentException("Invalid Identity ingress decision");
        }
    }
}
