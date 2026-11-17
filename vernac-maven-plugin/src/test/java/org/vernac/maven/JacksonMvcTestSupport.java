// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;
import org.springframework.http.converter.HttpMessageConverter;
/** Exercises the same Jackson 3 codecs as Boot 4. */
final class JacksonMvcTestSupport {
    private JacksonMvcTestSupport() {}
    static HttpMessageConverter<?> converter() {
        return new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter();
    }
}
