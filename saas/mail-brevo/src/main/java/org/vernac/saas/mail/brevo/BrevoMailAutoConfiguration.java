// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.mail.brevo;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.vernac.runtime.configuration.AdapterConfiguration;
@AutoConfiguration
public class BrevoMailAutoConfiguration {
 @Bean @Lazy @ConditionalOnMissingBean
 public BrevoMailTransport vernacBrevoMailTransport(Environment environment) {
  return new BrevoMailTransport(
   AdapterConfiguration.read(environment,"vernac.mail.brevo.api-key",String.class,"Brevo mail"),
   AdapterConfiguration.read(environment,"vernac.mail.sender-address",String.class,"Brevo mail"));
 }
}
