// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.subscriptions;

import java.util.*;
import org.vernac.saas.subscriptions.domain.*;

/** Finite, canonical domain snapshot; never a permission evaluator or a usage counter. */
public final class Benefits {
    public static final int MAX_BENEFITS = 64;
    private static final int MAX_ENCODED = 4608;
    private Benefits() {}

    public static BenefitSnapshot of(Map<BenefitKey, Allowance> allowances) {
        Objects.requireNonNull(allowances);
        if (allowances.size() > MAX_BENEFITS) throw invalid();
        var values = new TreeMap<String, Long>();
        allowances.forEach((key, amount) -> values.put(Objects.requireNonNull(key).value(), Objects.requireNonNull(amount).maximum()));
        return BenefitSnapshot.of(encode(values));
    }
    public static Allowance allowance(BenefitSnapshot snapshot, BenefitKey key) {
        return Allowance.of(decode(Objects.requireNonNull(snapshot).encoded()).getOrDefault(Objects.requireNonNull(key).value(), 0L));
    }
    public static Map<BenefitKey, Allowance> entries(BenefitSnapshot snapshot) {
        var result = new LinkedHashMap<BenefitKey, Allowance>();
        decode(Objects.requireNonNull(snapshot).encoded()).forEach((key, value) -> result.put(BenefitKey.of(key), Allowance.of(value)));
        return Collections.unmodifiableMap(result);
    }
    public static boolean valid(String encoded) {
        try { decode(encoded); return true; }
        catch (RuntimeException malformed) { return false; }
    }
    private static SortedMap<String, Long> decode(String encoded) {
        if (encoded == null || encoded.length() > MAX_ENCODED) throw invalid();
        var result = new TreeMap<String, Long>();
        if (encoded.isEmpty()) return result;
        String[] parts = encoded.split(",", -1);
        if (parts.length > MAX_BENEFITS) throw invalid();
        for (String part : parts) {
            int equals = part.indexOf('=');
            if (equals < 1) throw invalid();
            String key = part.substring(0, equals), value = part.substring(equals + 1);
            if (!key.matches("[a-z][a-z0-9-]{0,47}") || !value.matches("-1|0|[1-9][0-9]{0,18}")) throw invalid();
            long amount;
            try { amount = Long.parseLong(value); } catch (NumberFormatException overflow) { throw invalid(); }
            if (result.putIfAbsent(key, amount) != null) throw invalid();
        }
        if (!encode(result).equals(encoded)) throw invalid();
        return result;
    }
    private static String encode(SortedMap<String, Long> values) {
        return values.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).collect(java.util.stream.Collectors.joining(","));
    }
    private static SubscriptionFailure invalid() { return new SubscriptionFailure(SubscriptionFailure.Code.INVALID_CATALOG); }
}
