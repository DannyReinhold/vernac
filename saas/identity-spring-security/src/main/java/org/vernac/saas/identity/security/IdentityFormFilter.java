// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/** Explicitly install before CSRF and all parameter-reading filters on exact Identity POST paths. */
public final class IdentityFormFilter extends OncePerRequestFilter {
    public static final int MAX_BODY_BYTES = 16_384;
    private final Set<String> paths;

    public IdentityFormFilter(Set<String> paths) {
        if (paths == null || paths.isEmpty() || paths.size() > 16) throw new IllegalArgumentException("Invalid identity form paths");
        for (String path : paths) {
            if (path == null || !path.matches("/[a-zA-Z0-9/_-]+")) throw new IllegalArgumentException("Invalid identity form path");
        }
        this.paths = Set.copyOf(paths);
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!"POST".equals(request.getMethod()) || !paths.contains(request.getServletPath())) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        // A single source of form parameters; no query/body merge or multipart/container parsing.
        if (request.getQueryString() != null || request.getContentLengthLong() > MAX_BODY_BYTES) {
            reject(response, request.getContentLengthLong() > MAX_BODY_BYTES ? 413 : 400);
            return;
        }
        try {
            MediaType type = MediaType.parseMediaType(Objects.requireNonNullElse(request.getContentType(), ""));
            if (!MediaType.APPLICATION_FORM_URLENCODED.isCompatibleWith(type)
                    || type.getCharset() != null && !StandardCharsets.UTF_8.equals(type.getCharset())) {
                reject(response, 415);
                return;
            }
        } catch (IllegalArgumentException invalid) {
            reject(response, 415);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) { reject(response, 413); return; }
        final Map<String, String[]> parameters;
        try { parameters = parse(body); }
        catch (IllegalArgumentException | CharacterCodingException invalid) { reject(response, 400); return; }
        chain.doFilter(new FormRequest(request, parameters), response);
    }

    private static Map<String, String[]> parse(byte[] body) throws CharacterCodingException {
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
        Map<String, String[]> result = new LinkedHashMap<>();
        if (text.isEmpty()) return result;
        String[] fields = text.split("&", -1);
        if (fields.length > 16) throw new IllegalArgumentException();
        for (String field : fields) {
            int equal = field.indexOf('=');
            if (equal <= 0) throw new IllegalArgumentException();
            String name = decode(field.substring(0, equal));
            String value = decode(field.substring(equal + 1));
            if (!name.matches("[a-zA-Z_][a-zA-Z0-9_]{0,63}") || value.length() > 4096
                    || result.putIfAbsent(name, new String[]{value}) != null) throw new IllegalArgumentException();
        }
        return result;
    }

    private static String decode(String value) {
        String decoded = URLDecoder.decode(value, StandardCharsets.UTF_8);
        // URLDecoder replaces malformed percent-encoded UTF-8. Reject that ambiguity as well
        // as controls before credentials, CSRF, logging or handlers can inspect parameters.
        for (int i = 0; i < decoded.length(); i++) {
            char c = decoded.charAt(i);
            if (Character.isISOControl(c) || c == '\ufffd') throw new IllegalArgumentException();
        }
        return decoded;
    }

    private static void reject(HttpServletResponse response, int status) throws IOException {
        response.setStatus(status);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write("Identity form rejected");
    }

    private static final class FormRequest extends HttpServletRequestWrapper {
        private final Map<String, String[]> parameters;
        FormRequest(HttpServletRequest request, Map<String, String[]> parameters) { super(request); this.parameters = parameters; }
        @Override public String getParameter(String name) { String[] values = parameters.get(name); return values == null ? null : values[0]; }
        @Override public Enumeration<String> getParameterNames() { return Collections.enumeration(parameters.keySet()); }
        @Override public String[] getParameterValues(String name) { String[] values = parameters.get(name); return values == null ? null : values.clone(); }
        @Override public Map<String, String[]> getParameterMap() {
            Map<String, String[]> copy = new LinkedHashMap<>();
            parameters.forEach((key, values) -> copy.put(key, values.clone()));
            return Collections.unmodifiableMap(copy);
        }
    }
}
