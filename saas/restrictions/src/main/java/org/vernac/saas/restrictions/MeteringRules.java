// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.restrictions;

public final class MeteringRules {
    private MeteringRules() {}
    public static boolean validBudgetKey(String key) {
        return key != null && key.length() <= 180 && key.matches("[0-9a-f-]{36}/[a-z][a-z0-9-]{0,47}/[0-9a-f]{64}");
    }
    public static boolean validReservationKey(String key) {
        return key != null && key.length() <= 165 && key.matches("[0-9a-f-]{36}/[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,127}");
    }
    public static boolean validTotal(long consumed, long reserved) {
        return consumed >= 0 && reserved >= 0 && consumed <= Long.MAX_VALUE - reserved;
    }
    public static void requireRoom(long consumed, long reserved, long amount, long maximum) {
        if (amount < 0 || maximum < -1 || !validTotal(consumed, reserved) || amount > Long.MAX_VALUE - consumed - reserved)
            throw new RestrictionFailure(RestrictionFailure.Code.INVALID_FACT);
        if (maximum >= 0 && (consumed > maximum || reserved > maximum - consumed || amount > maximum - consumed - reserved))
            throw new RestrictionFailure(RestrictionFailure.Code.EXHAUSTED);
    }
    public static void requireReserved(long reserved, long amount) {
        if (amount < 0 || reserved < amount) throw new RestrictionFailure(RestrictionFailure.Code.INVALID_TRANSITION);
    }
    public static void requirePending(boolean pending) {
        if (!pending) throw new RestrictionFailure(RestrictionFailure.Code.INVALID_TRANSITION);
    }
}
