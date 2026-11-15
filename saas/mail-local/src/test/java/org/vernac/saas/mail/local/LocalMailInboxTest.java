// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.mail.local;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.vernac.saas.mail.domain.*;
class LocalMailInboxTest {
 static OutboundMail message(){return OutboundMail.of(MailAddress.of("recipient@example.test"),MailSubject.of("subject"),
  MailBody.of("private plain text"),MailBody.of("<p>private HTML</p>"),MailCorrelationId.create());}
 @Test void defaultAcceptsWithoutNetworkAndCapacityNeverEvictsLiveMessages() {
  var inbox=new LocalMailInbox(Clock.systemUTC(),new LocalMailInbox.Limits(1,1000,Duration.ofMinutes(10)));
  assertEquals(MailAcceptance.ACCEPTED,inbox.submit(message()).acceptance());
  assertEquals(MailAcceptance.RETRY,inbox.submit(message()).acceptance());assertEquals(1,inbox.previews().size());
  assertEquals("LocalMailPreview[REDACTED]",inbox.previews().getFirst().toString());
  assertThrows(UnsupportedOperationException.class,()->inbox.previews().clear());
  inbox.reset();assertTrue(inbox.previews().isEmpty());
  assertEquals(MailAcceptance.RETRY,new LocalMailInbox(Clock.systemUTC(),new LocalMailInbox.Limits(100,1,Duration.ofMinutes(1))).submit(message()).acceptance());
 }
 @Test void expiryAndScriptedFailuresFollowTheSameSubmissionContract() {
  var now=new AtomicReference<>(Instant.parse("2026-11-13T12:00:00Z"));
  Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId ignored){return this;}public Instant instant(){return now.get();}};
  var inbox=new LocalMailInbox(clock,new LocalMailInbox.Limits(1,1000,Duration.ofSeconds(10)));
  inbox.script(List.of(LocalMailInbox.Fault.RETRY,LocalMailInbox.Fault.REJECTED,LocalMailInbox.Fault.FAILURE,LocalMailInbox.Fault.ACCEPTED));
  assertEquals(MailAcceptance.RETRY,inbox.submit(message()).acceptance());
  assertEquals(MailAcceptance.REJECTED,inbox.submit(message()).acceptance());
  assertNull(assertThrows(IllegalStateException.class,()->inbox.submit(message())).getCause());
  assertEquals(MailAcceptance.ACCEPTED,inbox.submit(message()).acceptance());
  now.set(now.get().plusSeconds(10));assertTrue(inbox.previews().isEmpty());
  assertThrows(IllegalArgumentException.class,()->inbox.script(Collections.nCopies(65,LocalMailInbox.Fault.ACCEPTED)));
 }
}
