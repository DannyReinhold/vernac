// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import org.springframework.http.converter.HttpMessageConverter;

/** Matches the documented Boot 4 Jackson 2 binding for Vernac-generated DTO codecs. */
final class Jackson2MvcTestSupport {
    private Jackson2MvcTestSupport() {}

    // Spring 7 deprecates its Jackson 2 bridge. Generated codecs still target Jackson 2;
    // replacing this converter with Jackson 3 would stop exercising their wire contract.
    // Keep the compatibility exception here, rather than suppressing whole test classes.
    @SuppressWarnings("removal")
    static HttpMessageConverter<?> converter() {
        return new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter();
    }
}
