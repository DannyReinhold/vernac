// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;
import java.net.*;
import java.util.Objects;
import org.vernac.saas.mail.domain.*;
/** Fixed security messages; trusted origin only, bearer secret in fragment, never query/path. */
final class IdentityMailRenderer {
 private final URI origin; private final boolean loopback;
 IdentityMailRenderer(URI origin) {
  this.origin=Objects.requireNonNull(origin);
  String host=origin.getHost();
  loopback=host!=null && (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("[::1]"));
  if(!origin.isAbsolute() || origin.isOpaque() || host==null || origin.getRawUserInfo()!=null
   || origin.getRawQuery()!=null || origin.getRawFragment()!=null
   || !(origin.getPath().isEmpty() || origin.getPath().equals("/"))
   || !(origin.getScheme().equals("https") || (origin.getScheme().equals("http") && loopback)))
   throw new IllegalArgumentException("Invalid trusted identity mail origin");
 }
 boolean loopback(){return loopback;}
 OutboundMail render(IdentityMailContent content,IdentityDeliveryCrypto.Context context) {
  Objects.requireNonNull(content);Objects.requireNonNull(context);
  if(!content.locator().equals(context.locator()))throw new IllegalArgumentException("Invalid identity mail context");
  boolean verification=context.purpose()==org.vernac.saas.identity.persistence.domain.IdentityChallengePurpose.VERIFICATION;
  String title=verification?"Confirm your email address":"Reset your password";
  String path=verification?"/auth/verify":"/auth/recover";
  String link;
  try{link=new URI(origin.getScheme(),null,origin.getHost(),origin.getPort(),path,null,content.deliveryToken()).toASCIIString();}
  catch(URISyntaxException invalid){throw new IllegalArgumentException("Invalid identity mail link");}
  String text=title+"\n\nOpen this link and explicitly confirm the form. Opening the link alone changes nothing.\n"+link
   +"\n\nThis link expires at "+context.expiresAt()+". If you did not request it, ignore this message.";
  String escaped=link.replace("&","&amp;").replace("\"","&quot;").replace("<","&lt;").replace(">","&gt;");
  String html="<p>"+title+"</p><p><a href=\""+escaped+"\">Continue securely</a></p>"
   +"<p>Opening the link alone changes nothing. Explicitly confirm the form.</p><p>This link expires at "
   +context.expiresAt()+". If you did not request it, ignore this message.</p>";
  return OutboundMail.of(MailAddress.of(content.recipient()),MailSubject.of(title),MailBody.of(text),MailBody.of(html),
   MailCorrelationId.of(context.jobId()));
 }
 @Override public String toString(){return "IdentityMailRenderer[REDACTED]";}
}
