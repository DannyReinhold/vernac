// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.payments.stripe;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.vernac.runtime.configuration.AdapterConfiguration;
@AutoConfiguration
public class StripePaymentsAutoConfiguration {
    @Bean @Lazy @ConditionalOnMissingBean
    public StripePayments vernacStripePayments(Environment environment) {
        return new StripePayments(AdapterConfiguration.read(environment,"vernac.payments.stripe.api-key",String.class,"stripe payments"), AdapterConfiguration.read(environment,"vernac.payments.stripe.webhook-secret",String.class,"Stripe payments"));
    }
}
