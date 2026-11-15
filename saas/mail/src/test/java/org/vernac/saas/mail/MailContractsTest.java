// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.mail;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.vernac.runtime.DomainValidationException;
import org.vernac.saas.mail.domain.*;
class MailContractsTest {
 @Test void boundedValuesKeepClearContentOutOfStringRepresentations() {
  var mail=OutboundMail.of(MailAddress.of("recipient@example.test"),MailSubject.of("Synthetic private subject"),
   MailBody.of("Synthetic private text"),MailBody.of("<p>Synthetic private text</p>"),MailCorrelationId.create());
  assertEquals("OutboundMail[REDACTED]",mail.toString());
  assertEquals("MailAddress[REDACTED]",mail.recipient().toString());
  assertEquals("MailBody[REDACTED]",mail.text().toString());
  assertEquals("MailSubject[REDACTED]",mail.subject().toString());
  assertEquals("recipient@example.test",mail.recipient().encoded());
 }
 @Test void standardAddressParserAndHeaderBoundsRejectInjection() {
  for(String bad:new String[]{"","Name <a@example.test>","a@example.test\r\nBcc: other@example.test","a@@example.test","ü@example.test"})
   assertThrows(DomainValidationException.class,()->MailAddress.of(bad));
  assertThrows(DomainValidationException.class,()->MailSubject.of("subject\r\nInjected: true"));
  assertThrows(DomainValidationException.class,()->MailSubject.of("x".repeat(201)));
 }
 @Test void bodyUtf8SizeAndMalformedUnicodeAreBounded() {
  MailBody.of("x".repeat(30000));MailBody.of("ä".repeat(15000));
  assertThrows(DomainValidationException.class,()->MailBody.of("ä".repeat(15001)));
  assertThrows(DomainValidationException.class,()->MailBody.of("x"+(char)0xd800));
  assertThrows(DomainValidationException.class,()->MailBody.of(String.valueOf((char)0)));
 }
 @Test void outcomesHaveFiniteRetryHintsAndNoFalseAcceptance() {
  MailSubmission.of(MailAcceptance.ACCEPTED,MailRetryDelay.of(0));MailSubmission.of(MailAcceptance.RETRY,MailRetryDelay.of(1800));
  assertThrows(DomainValidationException.class,()->MailRetryDelay.of(1801));
  assertThrows(DomainValidationException.class,()->MailSubmission.of(MailAcceptance.ACCEPTED,MailRetryDelay.of(1)));
 }
}
