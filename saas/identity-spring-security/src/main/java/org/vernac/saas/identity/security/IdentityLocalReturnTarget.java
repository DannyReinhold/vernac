// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;

/** Conservative application-local redirect paths. No request host or forwarded header is trusted. */
public final class IdentityLocalReturnTarget {
    private final String fallback;

    public IdentityLocalReturnTarget(String fallback) {
        if (!safe(fallback)) throw new IllegalArgumentException("Invalid local return fallback");
        this.fallback = fallback;
    }

    /** Returns an absolute-path reference or the startup-validated local fallback. */
    public String resolve(String candidate) {
        return safe(candidate) ? candidate : fallback;
    }

    private static boolean safe(String value) {
        if (value == null || value.isEmpty() || value.length() > 2048 || value.charAt(0) != '/') return false;
        // Restrict to an unencoded path grammar. This avoids browser/proxy disagreement over
        // percent decoding, slash/backslash normalization, dot segments and control characters.
        if (value.length() > 1 && value.charAt(1) == '/') return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c == '/' || c == '-' || c == '_' || c == '.' || c == '~'
                    || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9')) return false;
        }
        for (String segment : value.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) return false;
        }
        return true;
    }

    @Override public String toString() { return "IdentityLocalReturnTarget[configured]"; }
}
