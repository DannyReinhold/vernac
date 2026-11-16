// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.billing.web;

import org.vernac.saas.payments.domain.PaymentProviderEnvironment;
import org.vernac.saas.payments.persistence.domain.PaymentProfileKey;

import java.util.*;

/** Explicit UI labels/configuration; selected provider identities remain frozen by PaymentFlow. */
public record BillingSettings(
        String applicationName, String productPath, List<PaymentChoice> payments) {
    public record PaymentChoice(
            PaymentProfileKey profile, String label, PaymentProviderEnvironment environment) {
        public PaymentChoice {
            Objects.requireNonNull(profile);
            Objects.requireNonNull(environment);
            label = text(label);
        }

        public boolean test() {
            return environment != PaymentProviderEnvironment.PRODUCTION;
        }
    }

    public BillingSettings {
        applicationName = text(applicationName);
        payments = List.copyOf(payments);
        if (productPath == null
                || !productPath.matches("/[a-zA-Z0-9/_-]*")
                || productPath.startsWith("//")
                || payments.isEmpty()
                || payments.size() > 8
                || payments.stream().map(PaymentChoice::profile).distinct().count()
                        != payments.size())
            throw new IllegalArgumentException("Invalid billing UI configuration");
    }

    private static String text(String value) {
        if (value == null
                || value.isBlank()
                || value.length() > 100
                || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid billing UI label");
        return value;
    }
}
