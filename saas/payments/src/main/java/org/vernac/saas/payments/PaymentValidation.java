// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.payments;

import java.net.URI;
import java.nio.charset.StandardCharsets;

public final class PaymentValidation {
    private PaymentValidation() {}
    public static boolean httpsUri(String input) {
        try {
            if (input == null || input.length() > 4096 || input.chars().anyMatch(c -> c <= 32 || c >= 127 || c == '\\')) return false;
            var uri = URI.create(input);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getRawUserInfo() == null && uri.getRawFragment() == null;
        } catch (RuntimeException invalid) { return false; }
    }
    public static boolean notice(String input) {
        if (input == null || input.isEmpty() || input.length() > 65536 || input.getBytes(StandardCharsets.UTF_8).length > 65536) return false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isHighSurrogate(c)) { if (++i >= input.length() || !Character.isLowSurrogate(input.charAt(i))) return false; }
            else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }
    public static boolean signature(String input) {
        return input != null && input.length() <= 2048 && input.chars().allMatch(c -> c >= 32 && c < 127);
    }
}
