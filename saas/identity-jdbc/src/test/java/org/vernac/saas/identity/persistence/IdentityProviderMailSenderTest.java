// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;
import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.vernac.saas.mail.domain.*;
import org.vernac.saas.identity.persistence.domain.IdentityChallengePurpose;
class IdentityProviderMailSenderTest {
 static MailProvider provider(MailProviderMode mode,AtomicReference<OutboundMail> captured,MailAcceptance outcome,int delay) {
  return new MailProvider() {
   public MailProviderMode mode(){return mode;}
   public MailSubmission submit(OutboundMail mail){captured.set(mail);return MailSubmission.of(outcome,MailRetryDelay.of(delay));}
  };
 }
 @Test void trustedOriginPurposeFragmentsAndStableCorrelationReachTheBoundProvider() {
  for(var purpose:IdentityChallengePurpose.values()){
   var captured=new AtomicReference<OutboundMail>();
   var token=IdentityChallengeToken.create();var content=new IdentityMailContent("Original@example.test",token);
   var context=new IdentityDeliveryCrypto.Context(UUID.randomUUID(),UUID.randomUUID(),purpose,token.locator(),Instant.parse("2026-11-13T12:00:00Z"));
   var sender=new IdentityProviderMailSender(provider(MailProviderMode.LIVE,captured,MailAcceptance.RETRY,1200),URI.create("https://identity.example.test"),false);
   assertEquals(1200,sender.submission(content,context).retryAfter().seconds());
   var mail=captured.get();assertEquals("Original@example.test",mail.recipient().encoded());
   assertEquals(context.jobId(),mail.correlation().value());
   String link=mail.text().text().lines().filter(x->x.startsWith("https://")).findFirst().orElseThrow();
   var uri=URI.create(link);assertNull(uri.getQuery());assertEquals(token.deliveryValue(),uri.getFragment());
   assertEquals(purpose==IdentityChallengePurpose.VERIFICATION?"/auth/verify":"/auth/recover",uri.getPath());
   assertFalse(mail.toString().contains(token.deliveryValue()));assertFalse(sender.toString().contains(token.deliveryValue()));
  }
 }
 @Test void simulationRequiresExplicitLoopbackAndUntrustedOriginsNeverReachTheProvider() {
  var p=provider(MailProviderMode.SIMULATED,new AtomicReference<>(),MailAcceptance.ACCEPTED,0);
  assertThrows(IllegalArgumentException.class,()->new IdentityProviderMailSender(p,URI.create("https://identity.example.test"),true));
  assertThrows(IllegalArgumentException.class,()->new IdentityProviderMailSender(p,URI.create("http://localhost:8080"),false));
  assertDoesNotThrow(()->new IdentityProviderMailSender(p,URI.create("http://127.0.0.1:8080"),true));
  for(String value:new String[]{"http://identity.example.test","https://user:secret@identity.example.test","https://identity.example.test/prefix","https://identity.example.test?target=other","https://identity.example.test#fragment","https://localhost.evil.test/path"})
   assertThrows(IllegalArgumentException.class,()->new IdentityMailRenderer(URI.create(value)));
 }
}
