// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.vernac.saas.identity.security.IdentityActionException;

/** Explicit internal-operator SPI. It installs no endpoint, automatic logger or per-user metric labels. */
public final class IdentityOperations {
 public enum Stage { DELIVERY, DELIVERY_SWEEP, CHALLENGES, INVITATIONS, REGISTRATIONS, ADMISSION }
 public record Passes(long completed,long unavailable) {}
 public record WorkerStatus(boolean running,Map<Stage,Passes> passes) {
  public WorkerStatus {passes=Map.copyOf(passes);}
 }
 public record Snapshot(WorkerStatus worker,long readyMail,long leasedMail,long cancelledMail,
  long oldestReadyAgeSeconds,long pendingRegistrations,long challenges,long invitations) {}
 private final NamedParameterJdbcTemplate jdbc;private final TransactionTemplate read;
 private final Clock clock;private final Supplier<WorkerStatus> worker;
 IdentityOperations(NamedParameterJdbcTemplate jdbc,PlatformTransactionManager manager,Clock clock,Supplier<WorkerStatus> worker){
  this.jdbc=jdbc;this.clock=clock;this.worker=worker;read=new TransactionTemplate(manager);
  read.setReadOnly(true);read.setTimeout(2);read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
 }
 /** Process-local pass counters; completion does not imply remote mail acceptance. */
 public WorkerStatus workerStatus(){return worker.get();}
 /** Fixed-cardinality aggregates only. Unavailable storage throws a safe technical reason, never fabricated zeros. */
 public Snapshot snapshot() {
  IdentityJourneys.boundary();
  try{return Objects.requireNonNull(read.execute(status->{
   String schema="\"org.vernac.saas.identity.persistence\".";
   return jdbc.queryForObject("SELECT count(*) FILTER (WHERE \"state\"='READY'), count(*) FILTER (WHERE \"state\"='LEASED'), count(*) FILTER (WHERE \"state\"='CANCELLED'), min(\"@createdAt\") FILTER (WHERE \"state\"='READY'),"
    +" (SELECT count(*) FROM "+schema+"\"IdentityPendingRegistration\"),"
    +" (SELECT count(*) FROM "+schema+"\"IdentityChallenge\"),"
    +" (SELECT count(*) FROM "+schema+"\"AccountInvitation\") FROM "+schema+"\"IdentityMailJob\"",Map.of(),(rs,n)->{
     var oldest=rs.getTimestamp(4);long age=oldest==null?0:Math.max(0,Duration.between(oldest.toInstant(),clock.instant()).getSeconds());
     return new Snapshot(worker.get(),rs.getLong(1),rs.getLong(2),rs.getLong(3),age,rs.getLong(5),rs.getLong(6),rs.getLong(7));
    });
  }));}catch(RuntimeException unavailable){throw new IdentityActionException(IdentityActionException.Code.UNAVAILABLE);}
 }
 @Override public String toString(){return "IdentityOperations[aggregate-only]";}
}
