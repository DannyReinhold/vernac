// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** Shared presentation for browser forms with a hash-pinned stylesheet. */
final class IdentityPageStyle {
    private static final String CSS = """
            * { box-sizing: border-box; }
            body { margin: 0; background: #eff5f4; color: #102e35; font: 16px/1.65 system-ui, sans-serif; }
            main { width: min(100% - 2rem, 34rem); margin: clamp(1rem, 7vw, 6rem) auto;
                   padding: clamp(1.5rem, 5vw, 3rem); background: white; border: 1px solid #d6e5e2;
                   border-radius: 1.5rem; box-shadow: 0 24px 64px #102e3514; }
            h1 { line-height: 1.1; letter-spacing: -.04em; font-size: clamp(1.8rem, 5vw, 2.75rem); }
            a { color: #09685d; text-underline-offset: .22em; }
            label { font-weight: 650; }
            input:not([type=hidden]) { width: 100%; margin: .35rem 0; padding: .65rem;
                   border: 1px solid #91aaa7; border-radius: .5rem; font: inherit; }
            button { padding: .75rem 1.25rem; border: 0; border-radius: .65rem;
                   background: #08675d; color: white; font: inherit; font-weight: 700; cursor: pointer; }
            .spaces { list-style: none; padding: 0; }
            .spaces li { display: grid; gap: .6rem; padding: 1rem; margin: .75rem 0; border: 1px solid #d6e5e2; border-radius: .8rem; }
            details { margin-top: 1.5rem; padding-top: 1rem; border-top: 1px solid #d6e5e2; }
            summary { cursor: pointer; font-weight: 650; }
            code { overflow-wrap: anywhere; }
            button:disabled { opacity: .55; cursor: default; }
            :focus-visible { outline: 3px solid #38a799; outline-offset: 4px; }
            """;
    static final String ELEMENT = "<style>" + CSS + "</style>";
    static final String POLICY = "; style-src 'sha256-" + hash() + "'";

    private IdentityPageStyle() {}

    private static String hash() {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(CSS.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Identity stylesheet unavailable");
        }
    }
}
