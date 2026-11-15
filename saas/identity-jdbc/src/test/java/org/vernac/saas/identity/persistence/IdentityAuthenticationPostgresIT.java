// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.security.authentication.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.vernac.saas.identity.domain.*;
import org.vernac.saas.identity.persistence.domain.*;
import org.vernac.saas.identity.security.*;
import org.vernac.saas.identity.security.credentials.*;
@EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
class IdentityAuthenticationPostgresIT {
 @TempDir Path root;
 static final Instant NOW=IdentityAdmissionPostgresIT.NOW;
 static final String PASSWORD="independent credential phrase number one 7839";
 static UsernamePasswordAuthenticationToken request(String address,String password) {
  return UsernamePasswordAuthenticationToken.unauthenticated(new IdentityLoginInput(address,IdentityAdmissionPostgresIT.ip(12)),password);
 }
 @Test void realSpringPasswordLoginReturnsOnlyNominalStampedIdentityAndRevocationSurvivesRestart() throws Exception {
  var id=new java.util.concurrent.atomic.AtomicReference<UserId>();
  var granted=new java.util.concurrent.atomic.AtomicReference<org.springframework.security.core.Authentication>();
  var clock=Clock.fixed(NOW,ZoneOffset.UTC);
  try(var work=new BoundedCredentialPasswords(new CredentialPasswords(OfflinePasswordBlocklist.bundled()))) {
   String encoded=work.encode(PASSWORD);
   new IdentityJdbcTestEnvironment(root).withDatabase(db->{
    db.boot.run(c->{
     var admission=new IdentityAdmissionPostgresIT.Fixture(c);admission.setup(50000);
     var f=new IdentityCredentialsPostgresIT.Fixture(c);id.set(f.pending());
     f.write(()->{
      assertTrue(f.credentials.enroll(id.get(),encoded));
      c.getBean(IdentityLoginAddressRepository.class).save(IdentityLoginAddress.reconstitute(id.get(),
       IdentityLoginKey.of("member@example.test"),IdentityDeliveryAddress.of("Member@Example.test"),NOW,NOW,0));
      return true;
     });f.activate(id.get());
     var provider=new JdbcIdentityAuthenticationProvider(admission.jdbc,admission.manager,admission.gate(NOW),work,clock);
     var input=request(" MEMBER@EXAMPLE.TEST ",PASSWORD);
     assertFalse(input.toString().contains("MEMBER@"));assertFalse(input.toString().contains(PASSWORD));
     var result=provider.authenticate(input);granted.set(result);
     assertNull(input.getCredentials());assertNull(result.getCredentials());assertTrue(result.isAuthenticated());
     assertTrue(result.getAuthorities().isEmpty());assertEquals(new IdentityPrincipal(id.get()),result.getPrincipal());
     assertEquals(new IdentitySessionStamp(0,NOW),result.getDetails());
     assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
     var wrong=request("member@example.test","wrong synthetic phrase");
     var known=assertThrows(BadCredentialsException.class,()->provider.authenticate(wrong));assertNull(wrong.getCredentials());
     var unknown=request("missing@example.test","wrong synthetic phrase");
     var missing=assertThrows(BadCredentialsException.class,()->provider.authenticate(unknown));assertNull(unknown.getCredentials());
     assertEquals(known.getMessage(),missing.getMessage());assertNull(known.getCause());assertNull(missing.getCause());
     assertEquals(3,admission.globalUsed());
     f.write(()->{var row=f.identities().byId(id.get());row.credentialChanged();return f.identities().save(row);});
     var metadata=new JdbcIdentitySessionEpochs(admission.jdbc,admission.manager);
     assertFalse(new IdentitySessionTrust(metadata,clock).current(result));
    });
    db.boot.run(c->{
     var admission=new IdentityAdmissionPostgresIT.Fixture(c);
     var metadata=new JdbcIdentitySessionEpochs(admission.jdbc,admission.manager);
     assertFalse(new IdentitySessionTrust(metadata,clock).current(granted.get()));
     var provider=new JdbcIdentityAuthenticationProvider(admission.jdbc,admission.manager,admission.gate(NOW),work,clock);
     assertEquals(new IdentitySessionStamp(1,NOW),provider.authenticate(request("member@example.test",PASSWORD)).getDetails());
     var f=new IdentityCredentialsPostgresIT.Fixture(c);
     f.write(()->{var row=f.identities().byId(id.get());row.disable();return f.identities().save(row);});
     assertThrows(BadCredentialsException.class,()->provider.authenticate(request("member@example.test",PASSWORD)));
    });
   });
  }
 }
 @Test void admissionRunsBeforeCredentialWorkAndAmbientTransactionsFailWithoutLeakingInputs() throws Exception {
  var clock=Clock.fixed(NOW,ZoneOffset.UTC);
  try(var work=new BoundedCredentialPasswords(new CredentialPasswords(OfflinePasswordBlocklist.bundled()))) {
   new IdentityJdbcTestEnvironment(root).withDatabase(db->db.boot.run(c->{
    var f=new IdentityAdmissionPostgresIT.Fixture(c);f.setup(50000);
    var provider=new JdbcIdentityAuthenticationProvider(f.jdbc,f.manager,f.gate(NOW),work,clock);
    long before=f.globalUsed();
    f.write(()->{
     var input=request("unknown@example.test",PASSWORD);
     var failure=assertThrows(AuthenticationServiceException.class,()->provider.authenticate(input));
     assertNull(input.getCredentials());assertNull(failure.getCause());assertFalse(failure.toString().contains(PASSWORD));return true;
    });assertEquals(before,f.globalUsed());
    for(int i=0;i<11;i++)assertThrows(BadCredentialsException.class,()->provider.authenticate(request("unknown@example.test",PASSWORD)));
    assertEquals(11,f.globalUsed());
    for(int i=0;i<49;i++)assertThrows(BadCredentialsException.class,()->provider.authenticate(request("unknown@example.test",PASSWORD)));
    var throttle=assertThrows(JdbcIdentityAuthenticationProvider.Throttled.class,()->provider.authenticate(request("unknown@example.test",PASSWORD)));
    assertTrue(throttle.retryAfterSeconds()>0);assertNull(throttle.getCause());
    assertNull(provider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated("ordinary Spring username","secret")));
   }));
  }
 }
}
