// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.mail.local;
import java.time.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
@AutoConfiguration
public class LocalMailAutoConfiguration {
 @Bean @Lazy @ConditionalOnMissingBean
 public LocalMailInbox vernacLocalMailInbox(Environment environment) {
  try {
   return new LocalMailInbox(Clock.systemUTC(),new LocalMailInbox.Limits(
    environment.getProperty("vernac.mail.local.max-messages",Integer.class,100),
    environment.getProperty("vernac.mail.local.max-bytes",Long.class,1024L*1024),
    Duration.parse(environment.getProperty("vernac.mail.local.retention","PT10M"))));
  } catch(RuntimeException invalid){throw new IllegalStateException("LOCAL_MAIL_CONFIGURATION_INVALID");}
 }
}
