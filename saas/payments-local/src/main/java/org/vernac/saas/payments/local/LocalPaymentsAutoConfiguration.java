// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.payments.local;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.vernac.saas.payments.PaymentFailure;

import java.time.Clock;

@AutoConfiguration
public class LocalPaymentsAutoConfiguration {
    @Bean
    @Lazy
    @ConditionalOnMissingBean
    public LocalPayments vernacLocalPayments(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager manager,
            Environment environment,
            ObjectProvider<Clock> clocks) {
        if (!Boolean.TRUE.equals(
                environment.getProperty("vernac.payments.local.allowed", Boolean.class, false)))
            throw new PaymentFailure(PaymentFailure.Code.CONFIGURATION);
        try {
            var scenario =
                    java.util.Arrays.stream(
                                    environment
                                            .getProperty("vernac.payments.local.scenario", "PAID")
                                            .split(",", -1))
                            .map(String::strip)
                            .map(LocalPayments.Step::valueOf)
                            .toList();
            return new LocalPayments(
                    jdbc, manager, scenario, clocks.getIfAvailable(Clock::systemUTC));
        } catch (RuntimeException invalid) {
            throw new PaymentFailure(PaymentFailure.Code.CONFIGURATION);
        }
    }
}
