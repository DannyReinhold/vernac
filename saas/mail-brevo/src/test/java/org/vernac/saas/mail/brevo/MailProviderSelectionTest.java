// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.mail.brevo;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.vernac.runtime.application.AdapterBindings;
import org.vernac.saas.mail.domain.*;
import org.vernac.saas.mail.local.*;
import org.vernac.saas.mail.local.adapter.outbound.NoopMailProvider;
import org.vernac.saas.mail.brevo.adapter.outbound.BrevoMailProvider;
class MailProviderSelectionTest {
 @Configuration(proxyBeanMethods=false)
 static class Application {
  @Bean static AdapterBindings bindings(org.springframework.core.env.Environment env) {
   return new AdapterBindings(env,List.of(new AdapterBindings.Binding(MailProvider.class,"org.vernac.saas.mail.MailProvider","vernac.mail.provider",null)),
    Map.of("org.vernac.saas.mail.local.NoopMailProvider",NoopMailProvider.class,
     "org.vernac.saas.mail.brevo.BrevoMailProvider",BrevoMailProvider.class));
  }
 }
 ApplicationContextRunner context(){return new ApplicationContextRunner().withConfiguration(
  AutoConfigurations.of(LocalMailAutoConfiguration.class,BrevoMailAutoConfiguration.class)).withUserConfiguration(Application.class);}
 @Test void normalBindingSelectsOnlyNoopWithoutAnyBrevoCredentialsOrStartupNetwork() {
  context().withPropertyValues("vernac.mail.provider=org.vernac.saas.mail.local.NoopMailProvider").run(c->{
   assertNull(c.getStartupFailure());var provider=c.getBean(MailProvider.class);assertEquals(MailProviderMode.SIMULATED,provider.mode());
   assertFalse(c.getSourceApplicationContext().getBeanFactory().containsSingleton("vernacBrevoMailTransport"));
   assertEquals(MailAcceptance.ACCEPTED,provider.submit(BrevoMailTransportTest.message()).acceptance());
   assertEquals(1,c.getBean(LocalMailInbox.class).previews().size());
  });
 }
 @Test void selectedBrevoRequiresConfigurationAndNeverFallsBackToNoop() {
  context().withPropertyValues("vernac.mail.provider=org.vernac.saas.mail.brevo.BrevoMailProvider")
   .run(c->assertNotNull(c.getStartupFailure()));
  context().withPropertyValues("vernac.mail.provider=org.vernac.saas.mail.brevo.BrevoMailProvider",
   "vernac.mail.brevo.api-key=synthetic-key-not-real","vernac.mail.sender-address=sender@example.test").run(c->{
    assertNull(c.getStartupFailure());assertEquals(MailProviderMode.LIVE,c.getBean(MailProvider.class).mode());
    assertFalse(c.getSourceApplicationContext().getBeanFactory().containsSingleton("vernacLocalMailInbox"));
   });
  context().withPropertyValues("vernac.mail.provider=unknown").run(c->assertNotNull(c.getStartupFailure()));
 }
}
