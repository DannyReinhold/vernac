// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.payments.persistence;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.vernac.saas.identity.domain.AccountId;
import org.vernac.saas.subscriptions.domain.*;
import org.vernac.saas.payments.domain.*;
import org.vernac.saas.payments.persistence.domain.*;
final class PaymentKeys {
    private PaymentKeys() {}
    static PaymentCycleKey cycle(AccountId account, SubscriptionId subscription, SubscriptionPeriod period) {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(period.start().toString().getBytes(StandardCharsets.US_ASCII)));
            return PaymentCycleKey.of(account.asString() + "/" + subscription.asString() + "/" + hash);
        } catch (NoSuchAlgorithmException impossible) { throw new PaymentProcessingFailure(PaymentProcessingFailure.Code.CONFIGURATION); }
    }
    static PaymentRequestKey request(AccountId account, PaymentOperationId request) { return PaymentRequestKey.of(account.asString() + "/" + request.asString()); }
    static PaymentAttemptKey attempt(PaymentOrderId order, int sequence) { return PaymentAttemptKey.of(order.asString() + "/" + sequence); }
    static PaymentRouteKey route(PaymentProfileKey profile, RemotePaymentId remote) { return PaymentRouteKey.of(profile.value() + "/" + remote.encoded()); }
}
