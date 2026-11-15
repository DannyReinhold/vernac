// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.payments.mollie;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.vernac.runtime.configuration.AdapterConfiguration;
@AutoConfiguration
public class MolliePaymentsAutoConfiguration {
    @Bean @Lazy @ConditionalOnMissingBean
    public MolliePayments vernacMolliePayments(Environment environment) {
        return new MolliePayments(AdapterConfiguration.read(environment,"vernac.payments.mollie.api-key",String.class,"mollie payments"));
    }
}
