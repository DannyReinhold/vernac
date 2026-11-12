// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security.credentials;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.JarFile;

class CredentialCorpusDistributionIT {
    private static final String PREFIX = "org/vernac/saas/identity/security/credentials/passwords/";
    private static final String PIN = "dbfb830d95e4f3da4fb7d7d277ed597de0ed7a18";

    @Test
    void packagedCorpusManifestAndLicenseMatchTheReviewedOfflineSnapshot() throws Exception {
        Path path = Path.of(System.getProperty("credential.jar"));
        assertTrue(Files.isRegularFile(path));
        try (var jar = new JarFile(path.toFile())) {
            var metadata = new Properties();
            metadata.load(
                    new InputStreamReader(
                            new ByteArrayInputStream(bytes(jar, PREFIX + "manifest.properties")),
                            StandardCharsets.UTF_8));
            assertEquals("vernac-password-corpus-1", metadata.getProperty("format"));
            assertEquals(PIN, metadata.getProperty("upstream.commit"));
            assertEquals("MIT", metadata.getProperty("license"));
            assertEquals(
                    "424a3e03a17df0a2bc2b3ca749d81b04e79d59cb7aeec8876a5a3f308d0caf51",
                    metadata.getProperty("upstream.sha256"));
            var manifest =
                    new OfflinePasswordBlocklist.Manifest(
                            metadata.getProperty("version"),
                            URI.create(metadata.getProperty("source")),
                            metadata.getProperty("license"),
                            metadata.getProperty("sha256"),
                            Integer.parseInt(metadata.getProperty("entries")));
            var corpus =
                    OfflinePasswordBlocklist.load(
                            new ByteArrayInputStream(bytes(jar, PREFIX + "blocked-v1.txt")),
                            manifest);
            assertEquals(10_908, corpus.entryCount());
            assertTrue(corpus.contains("passwordpassword"));
            byte[] license = bytes(jar, "META-INF/licenses/seclists-MIT.txt");
            assertEquals(
                    "3dbdc93d5f8829de0941744841730a09c106d0732e5ae0e98ca1d77be7ded66c",
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(license)));
            String notice =
                    new String(
                            bytes(jar, "META-INF/NOTICE-identity-passwords.txt"),
                            StandardCharsets.UTF_8);
            assertTrue(notice.contains(PIN));
            assertTrue(notice.contains("Copyright (c) 2018 Daniel Miessler"));
            assertTrue(
                    new String(bytes(jar, PREFIX + "curation.json"), StandardCharsets.UTF_8)
                            .contains("989092"));
            assertFalse(
                    jar.stream()
                            .anyMatch(entry ->
                                    entry.getName().startsWith("static/")
                                            || entry.getName().startsWith("public/")));
            assertFalse(
                    jar.stream()
                            .anyMatch(entry ->
                                    entry.getName()
                                            .contains("xato-net-10-million-passwords-1000000.txt")),
                    "The full upstream source is not runtime data");
        }
    }

    private byte[] bytes(JarFile jar, String name) throws IOException {
        var entry = jar.getJarEntry(name);
        assertNotNull(entry, "Missing corpus distribution entry: " + name);
        try (var input = jar.getInputStream(entry)) {
            return input.readAllBytes();
        }
    }
}
