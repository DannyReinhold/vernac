// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** PostgreSQL identifiers. Logical names are never case-folded or normalized. */
public final class SqlNames {
    private SqlNames() { }
    public static String physical(String logical) {
        if (logical.isEmpty() || logical.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid SQL identifier");
        if (logical.getBytes(StandardCharsets.UTF_8).length <= 63) return logical;
        StringBuilder prefix = new StringBuilder();
        for (int cp : logical.codePoints().toArray()) {
            String next = new String(Character.toChars(cp));
            if ((prefix.toString() + next).getBytes(StandardCharsets.UTF_8).length > 30) break;
            prefix.append(next);
        }
        return prefix + "~" + hash(logical).substring(0, 32);
    }
    public static String quote(String identifier) { return "\"" + identifier.replace("\"", "\"\"") + "\""; }
    public static String name(String logical) { return quote(physical(logical)); }
    public static String table(String namespace, String table) { return name(namespace) + "." + name(table); }
    public static String literal(String value) { return "'" + value.replace("'", "''") + "'"; }
    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
