// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.vernac.runtime.authorization.AuthorizationException;
import org.vernac.saas.identity.domain.*;
import org.vernac.saas.identity.persistence.adapter.outbound.jdbc.JdbcIdentityRepository;
import org.vernac.saas.identity.persistence.domain.IdentityRepository;
import org.vernac.saas.identity.security.IdentitySessionTrust;
/** Narrow trusted metadata lookup, no credential/hash, role, account or tenant selection API. */
final class JdbcIdentitySessionEpochs implements IdentitySessionTrust.EpochLookup {
 private final TransactionTemplate read;private final IdentityRepository identities;
 JdbcIdentitySessionEpochs(NamedParameterJdbcTemplate jdbc,PlatformTransactionManager manager) {
  identities=new JdbcIdentityRepository(Objects.requireNonNull(jdbc));
  read=new TransactionTemplate(Objects.requireNonNull(manager));read.setReadOnly(true);
 }
 @Override public OptionalLong currentActiveEpoch(UserId subject) {
  try {
   return Objects.requireNonNull(read.execute(status->{
    var identity=identities.existing(subject);
    return identity.isPresent() && identity.get().status()==IdentityStatus.ACTIVE
     ?OptionalLong.of(identity.get().securityEpoch().number()):OptionalLong.empty();
   }));
  } catch(RuntimeException unavailable){throw new AuthorizationException(AuthorizationException.Code.AUTHORIZATION_UNAVAILABLE);}
 }
 @Override public String toString(){return "JdbcIdentitySessionEpochs[REDACTED]";}
}
