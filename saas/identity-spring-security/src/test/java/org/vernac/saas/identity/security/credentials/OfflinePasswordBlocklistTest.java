// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security.credentials;

import static org.junit.jupiter.api.Assertions.*;
import static org.vernac.saas.identity.security.credentials.CredentialConfigurationException.Code.*;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

class OfflinePasswordBlocklistTest {
    @Test
    void loadsOnlyTheVerifiedVersionedCorpusAndMatchesWholeNormalizedPasswords() throws Exception {
        var corpus = CorpusFixture.load();
        assertEquals(1_000, corpus.entryCount());
        assertEquals("test-only-v1", corpus.version());
        assertTrue(corpus.contains("é test-only blocked password"));
        assertTrue(corpus.contains("TEST-ONLY blocked phrase 1"));
        assertFalse(corpus.contains("TEST-ONLY blocked phrase 1 plus a suffix"));
        assertFalse(corpus.contains("test-only blocked phrase 1"));
        assertEquals("OfflinePasswordBlocklist[redacted]", corpus.toString());
        assertEquals(
                "PasswordBlocklistManifest[redacted]",
                CorpusFixture.manifest(CorpusFixture.bytes(), 1_000).toString());
    }

    @Test
    void rejectsEmptyTinyWrongCountAndShortOnlyCorpora() throws Exception {
        for (byte[] bytes :
                List.of(
                        new byte[0],
                        "illustrative password only\n".getBytes(StandardCharsets.UTF_8),
                        shortOnly())) {
            assertInvalid(bytes, CorpusFixture.manifest(bytes, 1_000));
        }
        var bytes = CorpusFixture.bytes();
        assertInvalid(bytes, CorpusFixture.manifest(bytes, 1_001));
        assertThrows(
                CredentialConfigurationException.class,
                () -> OfflinePasswordBlocklist.load(null, null));
    }

    @Test
    void rejectsIntegrityMismatchMalformedUtf8AndDuplicateNormalizedEntries() throws Exception {
        var bytes = CorpusFixture.bytes();
        var changed = bytes.clone();
        changed[4] ^= 1;
        assertInvalid(changed, CorpusFixture.manifest(bytes, 1_000));
        byte[] malformed = {(byte) 0xc3, (byte) 0x28};
        assertInvalid(malformed, CorpusFixture.manifest(malformed, 1_000));
        var duplicate =
                (new String(bytes, StandardCharsets.UTF_8) + "é test-only blocked password\n")
                        .getBytes(StandardCharsets.UTF_8);
        assertInvalid(duplicate, CorpusFixture.manifest(duplicate, 1_001));
    }

    @Test
    void boundsResourceSizeAndAlwaysClosesTheInputWithoutRetainingReadErrors() throws Exception {
        byte[] oversized = new byte[8 * 1_024 * 1_024 + 1];
        var manifest = CorpusFixture.manifest(oversized, 1_000);
        assertInvalid(oversized, manifest);
        boolean[] closed = {false};
        var broken =
                new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("seeded-secret-read-error");
                    }

                    @Override
                    public void close() {
                        closed[0] = true;
                    }
                };
        var error =
                assertThrows(
                        CredentialConfigurationException.class,
                        () -> OfflinePasswordBlocklist.load(broken, manifest));
        assertTrue(closed[0]);
        assertNull(error.getCause());
        assertFalse(error.toString().contains("seeded-secret"));
        var tracked =
                new ByteArrayInputStream(CorpusFixture.bytes()) {
                    @Override
                    public void close() {
                        closed[0] = true;
                    }
                };
        closed[0] = false;
        assertThrows(
                CredentialConfigurationException.class,
                () -> OfflinePasswordBlocklist.load(tracked, null));
        assertTrue(closed[0]);
    }

    @Test
    void rejectsUnsafeOrUnversionedManifestWithoutEchoingMetadata() throws Exception {
        byte[] bytes = CorpusFixture.bytes();
        var valid = CorpusFixture.manifest(bytes, 1_000);
        for (var invalid :
                List.of(
                        new OfflinePasswordBlocklist.Manifest(
                                "", valid.source(), valid.license(), valid.sha256(), 1_000),
                        new OfflinePasswordBlocklist.Manifest(
                                valid.version(),
                                java.net.URI.create("https://user:seeded-secret@example.com/data"),
                                valid.license(),
                                valid.sha256(),
                                1_000),
                        new OfflinePasswordBlocklist.Manifest(
                                valid.version(), valid.source(), "", valid.sha256(), 1_000),
                        new OfflinePasswordBlocklist.Manifest(
                                valid.version(),
                                valid.source(),
                                valid.license(),
                                "bad-digest",
                                1_000))) {
            assertInvalid(bytes, invalid);
        }
    }

    private byte[] shortOnly() {
        var text = new StringBuilder();
        for (int i = 0; i < 1_000; i++) text.append("weak").append(i).append('\n');
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void assertInvalid(byte[] bytes, OfflinePasswordBlocklist.Manifest manifest) {
        var error =
                assertThrows(
                        CredentialConfigurationException.class,
                        () ->
                                OfflinePasswordBlocklist.load(
                                        new ByteArrayInputStream(bytes), manifest));
        assertEquals(BLOCKLIST_INVALID, error.code());
        assertNull(error.getCause());
        assertFalse(error.toString().contains("seeded-secret"));
    }
}
