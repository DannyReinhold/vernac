// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.restrictions;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
import org.vernac.saas.identity.domain.AccountId;
import org.vernac.saas.subscriptions.domain.BenefitKey;
import org.vernac.saas.restrictions.domain.*;

public final class MeteringKeys {
    private MeteringKeys() {}
    public static BudgetKey budget(AccountId account, BenefitKey benefit, UsageWindow window) {
        try {
            String period = window.start().toString() + "/" + window.end().toString();
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(period.getBytes(StandardCharsets.US_ASCII)));
            return BudgetKey.of(account.asString() + "/" + benefit.value() + "/" + hash);
        } catch (NoSuchAlgorithmException unavailable) { throw new RestrictionFailure(RestrictionFailure.Code.CONFIGURATION); }
    }
    public static ReservationKey reservation(AccountId account, UsageOperationKey operation) {
        return ReservationKey.of(account.asString() + "/" + operation.encoded());
    }
}
