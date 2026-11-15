// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.vernac.saas.identity.domain.*;
import org.vernac.saas.identity.persistence.domain.*;
import org.vernac.saas.identity.security.*;
@EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
class IdentitySessionEpochPostgresIT {
 @TempDir Path root;
 @Test void freshMetadataRejectsRevokedEpochsAndDisabledIdentitiesAcrossRestart() throws Exception {
  var subject=new java.util.concurrent.atomic.AtomicReference<UserId>();
  var now=Instant.parse("2026-11-13T12:00:00Z");
  new IdentityJdbcTestEnvironment(root).withDatabase(db->{
   db.boot.run(c->{
    assertNull(c.getStartupFailure());var tx=new TransactionTemplate(c.getBean(PlatformTransactionManager.class));
    subject.set(tx.execute(s->{var identity=Identity.create(IdentityStatus.ACTIVE,IdentitySecurityEpoch.of(0));c.getBean(IdentityRepository.class).save(identity);return identity.id();}));
    var metadata=new JdbcIdentitySessionEpochs(c.getBean(NamedParameterJdbcTemplate.class),c.getBean(PlatformTransactionManager.class));
    var trust=new IdentitySessionTrust(metadata,Clock.fixed(now,ZoneOffset.UTC));
    var token=UsernamePasswordAuthenticationToken.authenticated(new IdentityPrincipal(subject.get()),null,List.of());
    token.setDetails(new IdentitySessionStamp(0,now));assertTrue(trust.current(token));
    tx.executeWithoutResult(s->{var repo=c.getBean(IdentityRepository.class);var row=repo.byId(subject.get());row.credentialChanged();repo.save(row);});
    assertFalse(trust.current(token));token.setDetails(new IdentitySessionStamp(1,now));assertTrue(trust.current(token));
   });
   db.boot.run(c->{
    assertNull(c.getStartupFailure());var tx=new TransactionTemplate(c.getBean(PlatformTransactionManager.class));
    var metadata=new JdbcIdentitySessionEpochs(c.getBean(NamedParameterJdbcTemplate.class),c.getBean(PlatformTransactionManager.class));
    var trust=new IdentitySessionTrust(metadata,Clock.fixed(now,ZoneOffset.UTC));
    var token=UsernamePasswordAuthenticationToken.authenticated(new IdentityPrincipal(subject.get()),null,List.of());
    token.setDetails(new IdentitySessionStamp(0,now));assertFalse(trust.current(token));
    token.setDetails(new IdentitySessionStamp(1,now));assertTrue(trust.current(token));
    tx.executeWithoutResult(s->{var repo=c.getBean(IdentityRepository.class);var row=repo.byId(subject.get());row.disable();repo.save(row);});
    token.setDetails(new IdentitySessionStamp(2,now));assertFalse(trust.current(token));
    assertTrue(metadata.currentActiveEpoch(UserId.create()).isEmpty());
   });
  });
 }
}
