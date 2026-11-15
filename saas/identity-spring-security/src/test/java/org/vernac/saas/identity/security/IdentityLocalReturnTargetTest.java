// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class IdentityLocalReturnTargetTest {
    private final IdentityLocalReturnTarget targets = new IdentityLocalReturnTarget("/workspace");

    @Test void preservesOrdinaryApplicationPaths() {
        for (String path : new String[]{"/", "/workspace", "/projects/123/tasks", "/v1.0/a-b_c~d"})
            assertEquals(path, targets.resolve(path));
    }

    @Test void rejectsBrowserAndProxyRedirectAmbiguities() {
        for (String path : new String[]{"https://example.invalid", "//example.invalid", "/\\example.invalid",
                "\\example.invalid", "/%2fexample.invalid", "/%252fexample.invalid", "/a/../b", "/./a",
                "/a?next=https://example.invalid", "/a#secret", "/a;jsessionid=secret", "/a\r\nLocation:x",
                "/a\t", "/a b", "/\u0000", "/ü", "", "/" + "a".repeat(2048)})
            assertEquals("/workspace", targets.resolve(path));
        assertEquals("/workspace", targets.resolve(null));
    }

    @Test void rejectsInvalidFallbackAtStartup() {
        for (String path : new String[]{"//example.invalid", "relative", "/a?query", ""})
            assertThrows(IllegalArgumentException.class, () -> new IdentityLocalReturnTarget(path));
        assertThrows(IllegalArgumentException.class, () -> new IdentityLocalReturnTarget(null));
    }
}
