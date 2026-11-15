// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.saas.identity.security.*;
import org.vernac.saas.identity.security.credentials.*;
@EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
class IdentityOperationsPostgresIT {
 @TempDir Path root;
 static void await(BooleanSupplier condition)throws Exception {
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
  do{if(condition.getAsBoolean())return;Thread.sleep(10);}while(System.nanoTime()-deadline<0);
  fail("Expected bounded worker observation");
 }
 @Test void aggregateMonitoringDistinguishesRealBacklogWorkerFailureAndRecoveryWithoutSensitiveLabels()throws Exception {
  try(var passwords=new BoundedCredentialPasswords(new CredentialPasswords(OfflinePasswordBlocklist.bundled()))) {
   new IdentityJdbcTestEnvironment(root).withDatabase(db->db.boot.run(context->{
    try(var f=new IdentityJourneysPostgresIT.Fixture(context,passwords,new IdentityJourneysPostgresIT.ClockControl())) {
     var monitor=f.flow.operations();var empty=monitor.snapshot();assertFalse(empty.worker().running());assertEquals(0,empty.readyMail());
     f.flow.requestRegistration(IdentityJourneysPostgresIT.ADDRESS,f.source());f.clock.now=f.clock.now.plusSeconds(30);
     var pending=monitor.snapshot();assertEquals(1,pending.readyMail());assertEquals(1,pending.pendingRegistrations());assertEquals(1,pending.challenges());assertEquals(0,pending.invitations());assertEquals(30,pending.oldestReadyAgeSeconds());
     String text=pending.toString();assertFalse(text.contains(IdentityJourneysPostgresIT.ADDRESS));assertFalse(text.contains("token"));assertFalse(text.contains("envelope"));
     assertEquals(Set.of(IdentityOperations.Stage.values()),pending.worker().passes().keySet());
     assertThrows(UnsupportedOperationException.class,()->pending.worker().passes().clear());
     f.start();String token=f.token("verify");
     await(()->monitor.workerStatus().passes().get(IdentityOperations.Stage.DELIVERY).completed()>0);
     assertTrue(monitor.workerStatus().running());assertFalse(monitor.snapshot().toString().contains(token));
     try(var admin=java.sql.DriverManager.getConnection(db.url,db.credentials);var sql=admin.createStatement()) {
      sql.execute("REVOKE SELECT ON \"org.vernac.saas.identity.persistence\".\"IdentityMailJob\" FROM "+db.role);
      var failure=assertThrows(IdentityActionException.class,monitor::snapshot);assertEquals(IdentityActionException.Code.UNAVAILABLE,failure.code());assertNull(failure.getCause());
      await(()->monitor.workerStatus().passes().get(IdentityOperations.Stage.DELIVERY).unavailable()>0);
      long before=monitor.workerStatus().passes().get(IdentityOperations.Stage.DELIVERY).completed();
      sql.execute("GRANT SELECT ON \"org.vernac.saas.identity.persistence\".\"IdentityMailJob\" TO "+db.role);
      await(()->monitor.workerStatus().passes().get(IdentityOperations.Stage.DELIVERY).completed()>before);
      assertEquals(0,monitor.snapshot().readyMail());assertTrue(f.flow.verify(token,IdentityJourneysPostgresIT.PASSWORD,f.source()));
      assertEquals(0,monitor.snapshot().pendingRegistrations());
     }
     try(var scope=org.vernac.runtime.tenancy.TenantContext.openVerified(org.vernac.runtime.tenancy.TenantId.of(UUID.randomUUID()))){
      assertEquals(IdentityActionException.Code.UNAVAILABLE,assertThrows(IdentityActionException.class,monitor::snapshot).code());
     }
    }
   }));
  }
 }
}
