// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.vernac.saas.identity.domain.UserId;
import org.vernac.saas.identity.persistence.domain.*;
import org.vernac.saas.identity.security.*;
import org.vernac.saas.identity.security.credentials.*;

@EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
class IdentityJdbcAuthenticationAssemblyPostgresIT {
    @TempDir Path root;
    private static final Instant NOW=Instant.parse("2026-11-15T12:00:00Z");
    private static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    private static final String PASSWORD="independent credential phrase number one 7839";

    @Test void publicAssemblyAuthenticatesRealCredentialsAndRetainsAdmissionAndRevocationAcrossRestart() throws Exception {
        var subject=new AtomicReference<UserId>();var authenticated=new AtomicReference<Authentication>();
        try(var work=new BoundedCredentialPasswords(new CredentialPasswords(OfflinePasswordBlocklist.bundled()))) {
            String encoded=work.encode(PASSWORD);
            new IdentityJdbcTestEnvironment(root).withDatabase(db->{
                db.boot.run(context->{
                    assertNull(context.getStartupFailure());
                    var jdbc=context.getBean(NamedParameterJdbcTemplate.class);var transactions=context.getBean(PlatformTransactionManager.class);
                    var assembly=IdentityJdbcAuthentication.initializeDefaultPolicy(jdbc,transactions,CLOCK,"deployment-v1",IdentityAdmissionPostgresIT.key(7),work);
                    var fixture=new IdentityCredentialsPostgresIT.Fixture(context);subject.set(fixture.pending());
                    fixture.write(()->{
                        assertTrue(fixture.credentials.enroll(subject.get(),encoded));
                        context.getBean(IdentityLoginAddressRepository.class).save(IdentityLoginAddress.reconstitute(subject.get(),
                            IdentityLoginKey.of("member@example.test"),IdentityDeliveryAddress.of("Member@Example.test"),NOW,NOW,0));return true;
                    });fixture.activate(subject.get());
                    var input=IdentityAuthenticationPostgresIT.request("member@example.test",PASSWORD);
                    var result=assembly.authenticationManager().authenticate(input);authenticated.set(result);
                    assertNull(input.getCredentials());assertNull(result.getCredentials());assertTrue(assembly.sessionTrust().current(result));
                    assertEquals(new IdentityPrincipal(subject.get()),result.getPrincipal());
                    assertTrue(assembly.requestAdmission().tryAcquire().admitted());
                    assembly.maintainAdmission();
                    assertEquals(2,new IdentityAdmissionPostgresIT.Fixture(context).globalUsed());
                });
                db.boot.run(context->{
                    assertNull(context.getStartupFailure());
                    var jdbc=context.getBean(NamedParameterJdbcTemplate.class);var transactions=context.getBean(PlatformTransactionManager.class);
                    var assembly=IdentityJdbcAuthentication.initializeDefaultPolicy(jdbc,transactions,CLOCK,"deployment-v1",IdentityAdmissionPostgresIT.key(7),work);
                    assertTrue(assembly.sessionTrust().current(authenticated.get()));
                    assertEquals(2,new IdentityAdmissionPostgresIT.Fixture(context).globalUsed());
                    var fixture=new IdentityCredentialsPostgresIT.Fixture(context);
                    fixture.write(()->{var identity=fixture.identities().byId(subject.get());identity.credentialChanged();return fixture.identities().save(identity);});
                    assertFalse(assembly.sessionTrust().current(authenticated.get()));
                    assertNotNull(assembly.authenticationManager().authenticate(IdentityAuthenticationPostgresIT.request("member@example.test",PASSWORD)));
                });
            });
        }
    }

    @Test void bootstrapRejectsAmbientTransactionsAndKeyDriftWithoutResettingPersistedAdmission() throws Exception {
        try(var work=new BoundedCredentialPasswords(new CredentialPasswords(OfflinePasswordBlocklist.bundled()))) {
            new IdentityJdbcTestEnvironment(root).withDatabase(db->db.boot.run(context->{
                assertNull(context.getStartupFailure());var jdbc=context.getBean(NamedParameterJdbcTemplate.class);
                var transactions=context.getBean(PlatformTransactionManager.class);
                var assembly=IdentityJdbcAuthentication.initializeDefaultPolicy(jdbc,transactions,CLOCK,"deployment-v1",IdentityAdmissionPostgresIT.key(7),work);
                assertThrows(BadCredentialsException.class,()->assembly.authenticationManager().authenticate(IdentityAuthenticationPostgresIT.request("unknown@example.test",PASSWORD)));
                var fixture=new IdentityAdmissionPostgresIT.Fixture(context);assertEquals(1,fixture.globalUsed());
                fixture.write(()->{
                    var error=assertThrows(IllegalStateException.class,()->IdentityJdbcAuthentication.initializeDefaultPolicy(jdbc,transactions,CLOCK,
                            "deployment-v1",IdentityAdmissionPostgresIT.key(7),work));
                    assertNull(error.getCause());return true;
                });
                var error=assertThrows(IllegalStateException.class,()->IdentityJdbcAuthentication.initializeDefaultPolicy(jdbc,transactions,CLOCK,
                        "deployment-v1",IdentityAdmissionPostgresIT.key(9),work));
                assertEquals("Identity JDBC authentication configuration unavailable",error.getMessage());assertNull(error.getCause());
                assertEquals(1,fixture.globalUsed());
            }));
        }
    }
}
