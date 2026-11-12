// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security.credentials;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Synthetic TEST-ONLY corpus; not shipped data and not evidence of compromised-password coverage.
 */
final class CorpusFixture {
    private CorpusFixture() {}

    static byte[] bytes() {
        var text = new StringBuilder("e\u0301 test-only blocked password\n");
        for (int i = 1; i < 1_000; i++)
            text.append("TEST-ONLY blocked phrase ").append(i).append('\n');
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    static OfflinePasswordBlocklist.Manifest manifest(byte[] bytes, int count) throws Exception {
        return new OfflinePasswordBlocklist.Manifest(
                "test-only-v1",
                URI.create("https://example.invalid/test-only-corpus"),
                "CC0-1.0",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                count);
    }

    static OfflinePasswordBlocklist load() throws Exception {
        byte[] bytes = bytes();
        return OfflinePasswordBlocklist.load(
                new ByteArrayInputStream(bytes), manifest(bytes, 1_000));
    }
}
