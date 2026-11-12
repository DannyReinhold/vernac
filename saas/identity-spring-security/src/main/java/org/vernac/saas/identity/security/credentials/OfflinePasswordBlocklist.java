// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security.credentials;

import static org.vernac.saas.identity.security.credentials.CredentialConfigurationException.Code.BLOCKLIST_INVALID;

import org.jspecify.annotations.Nullable;

import java.io.*;
import java.net.URI;
import java.nio.charset.*;
import java.security.*;
import java.util.*;

/**
 * Immutable offline corpus. Integrity checks do not replace human provenance/license/content
 * review.
 */
public final class OfflinePasswordBlocklist {
    private static final int MAX_BYTES = 8 * 1_024 * 1_024;
    private final Set<String> entries;
    private final String version;

    /** SHA-256 covers the exact UTF-8 resource bytes, including line endings. */
    public record Manifest(String version, URI source, String license, String sha256, int entries) {
        @Override
        public String toString() {
            return "PasswordBlocklistManifest[redacted]";
        }
    }

    private OfflinePasswordBlocklist(Set<String> entries, String version) {
        this.entries = Set.copyOf(entries);
        this.version = version;
    }

    /**
     * Reads and closes the supplied local resource. One password per UTF-8 line; no comments,
     * trimming or case folding. A final line separator is optional. Duplicate NFC entries fail. No
     * corpus or permissive fallback is installed automatically.
     */
    public static OfflinePasswordBlocklist load(
            @Nullable InputStream input, @Nullable Manifest manifest) {
        if (input == null) throw invalid();
        try (input) {
            validate(manifest);
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES
                    || !HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                            .equals(manifest.sha256())) {
                throw invalid();
            }
            var decoder =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT);
            var values = new HashSet<String>();
            int eligible = 0;
            try (var reader =
                    new BufferedReader(
                            new InputStreamReader(new ByteArrayInputStream(bytes), decoder))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String normalized = PasswordInput.normalize(line);
                    if (!values.add(normalized) || values.size() > 100_000) throw invalid();
                    if (normalized.codePointCount(0, normalized.length()) >= 15) eligible++;
                }
            }
            // Sanity floors reject illustrative/no-op lists, not certify corpus quality.
            if (values.size() != manifest.entries() || eligible < 100) throw invalid();
            return new OfflinePasswordBlocklist(values, manifest.version());
        } catch (IOException | NoSuchAlgorithmException | RuntimeException failed) {
            throw invalid();
        }
    }

    private static void validate(@Nullable Manifest manifest) {
        if (manifest == null
                || manifest.version() == null
                || manifest.source() == null
                || manifest.license() == null
                || manifest.sha256() == null
                || !manifest.version().matches("[A-Za-z0-9._-]{1,64}")
                || !manifest.sha256().matches("[0-9a-f]{64}")
                || !manifest.license().matches("[A-Za-z0-9.+() -]{1,128}")
                || !"https".equals(manifest.source().getScheme())
                || manifest.source().getHost() == null
                || manifest.source().getRawUserInfo() != null
                || manifest.source().getRawQuery() != null
                || manifest.source().getRawFragment() != null
                || manifest.source().toString().length() > 2_048
                || manifest.entries() < 1_000
                || manifest.entries() > 100_000) {
            throw invalid();
        }
    }

    boolean contains(String normalized) {
        return entries.contains(normalized);
    }

    public String version() {
        return version;
    }

    public int entryCount() {
        return entries.size();
    }

    @Override
    public String toString() {
        return "OfflinePasswordBlocklist[redacted]";
    }

    private static CredentialConfigurationException invalid() {
        return new CredentialConfigurationException(BLOCKLIST_INVALID);
    }
}
