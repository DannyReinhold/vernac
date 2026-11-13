// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.mail.local;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.vernac.saas.mail.domain.*;
/** Bounded local simulation. Never sends or logs. UI authorization is a separate boundary. */
public final class LocalMailInbox {
 public enum Fault { ACCEPTED, RETRY, REJECTED, FAILURE }
 public record Limits(int messages,long bytes,Duration retention) {
  public Limits {
   if(messages<1 || messages>1000 || bytes<1 || bytes>64*1024*1024 || retention==null
    || retention.isNegative() || retention.isZero() || retention.compareTo(Duration.ofHours(24))>0)
    throw new IllegalArgumentException("LOCAL_MAIL_CONFIGURATION_INVALID");
  }
  public static Limits defaults(){return new Limits(100,1024*1024,Duration.ofMinutes(10));}
 }
 public static final class Preview {
  private final OutboundMail message; private final Instant expiresAt; private final long bytes;
  private Preview(OutboundMail message,Instant expiresAt,long bytes){this.message=message;this.expiresAt=expiresAt;this.bytes=bytes;}
  public OutboundMail message(){return message;}
  public Instant expiresAt(){return expiresAt;}
  @Override public String toString(){return "LocalMailPreview[REDACTED]";}
 }
 private final Clock clock; private final Limits limits;
 private final ArrayDeque<Preview> inbox=new ArrayDeque<>();
 private final ArrayDeque<Fault> script=new ArrayDeque<>();
 private long bytes;
 public LocalMailInbox(Clock clock,Limits limits){this.clock=Objects.requireNonNull(clock);this.limits=Objects.requireNonNull(limits);}
 public synchronized MailSubmission submit(OutboundMail message) {
  Objects.requireNonNull(message); purge(); Fault fault=script.isEmpty()?Fault.ACCEPTED:script.removeFirst();
  if(fault==Fault.FAILURE)throw new IllegalStateException("LOCAL_MAIL_SIMULATED_FAILURE");
  if(fault!=Fault.ACCEPTED)return result(fault==Fault.RETRY?MailAcceptance.RETRY:MailAcceptance.REJECTED);
  long size=message.recipient().encoded().getBytes(StandardCharsets.UTF_8).length
   +message.subject().text().getBytes(StandardCharsets.UTF_8).length
   +message.text().text().getBytes(StandardCharsets.UTF_8).length
   +message.html().text().getBytes(StandardCharsets.UTF_8).length+36;
  if(inbox.size()>=limits.messages() || size>limits.bytes()-bytes)return result(MailAcceptance.RETRY);
  inbox.addLast(new Preview(message,clock.instant().plus(limits.retention()),size));bytes+=size;
  return result(MailAcceptance.ACCEPTED);
 }
 public synchronized List<Preview> previews(){purge();return List.copyOf(inbox);}
 public synchronized void script(List<Fault> sequence) {
  if(sequence==null || sequence.size()>64 || sequence.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("LOCAL_MAIL_SCRIPT_INVALID");
  script.clear();script.addAll(sequence);
 }
 public synchronized void reset(){inbox.clear();script.clear();bytes=0;}
 private void purge() {
  var it=inbox.iterator();Instant now=clock.instant();
  while(it.hasNext()){var item=it.next();if(!now.isBefore(item.expiresAt)){bytes-=item.bytes;it.remove();}}
 }
 private static MailSubmission result(MailAcceptance acceptance){return MailSubmission.of(acceptance,MailRetryDelay.of(acceptance==MailAcceptance.RETRY?60:0));}
 @Override public String toString(){return "LocalMailInbox[SIMULATED,REDACTED]";}
}
