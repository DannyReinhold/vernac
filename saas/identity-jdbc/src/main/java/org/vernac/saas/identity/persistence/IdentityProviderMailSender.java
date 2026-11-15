// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;
import java.net.URI;
import java.util.Objects;
import org.vernac.saas.mail.domain.*;
/** Bridge to the ordinary application-bound provider. Never selects a provider itself. */
final class IdentityProviderMailSender implements JdbcIdentityMailWorker.Sender {
 private final MailProvider provider;private final IdentityMailRenderer renderer;
 IdentityProviderMailSender(MailProvider provider,URI trustedOrigin,boolean allowLocalSimulation) {
  this.provider=Objects.requireNonNull(provider);renderer=new IdentityMailRenderer(trustedOrigin);
  var mode=Objects.requireNonNull(provider.mode());
  if(mode==MailProviderMode.SIMULATED && !(allowLocalSimulation && renderer.loopback()))
   throw new IllegalArgumentException("Identity mail simulation requires explicit loopback development mode");
 }
 @Override public boolean submit(IdentityMailContent content,IdentityDeliveryCrypto.Context context) {
  return submission(content,context).acceptance()==MailAcceptance.ACCEPTED;
 }
 @Override public MailSubmission submission(IdentityMailContent content,IdentityDeliveryCrypto.Context context) {
  return Objects.requireNonNull(provider.submit(renderer.render(content,context)));
 }
 @Override public String toString(){return "IdentityProviderMailSender[REDACTED]";}
}
