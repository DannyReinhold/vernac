// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.http;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.vernac.runtime.json.DtoContractException;

/** One mutable builder per invocation; substitutions are encoded as data, never URI syntax. */
@NullMarked
public final class HttpRequests {
    private HttpRequests() { }
    public static Builder at(URI base, String template) { return new Builder(base, template); }

    @NullMarked
    public static final class Builder {
        private static final Pattern SLOT = Pattern.compile("\\{([^{}]+)}");
        private final URI base;
        private final String template;
        private final Map<String, String> paths = new LinkedHashMap<>();
        private final Map<String, String> query = new LinkedHashMap<>();
        private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        private Builder(URI base, String template) {
            this.base = Objects.requireNonNull(base);
            this.template = Objects.requireNonNull(template);
            if (!("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
                    || base.getHost() == null || base.getRawUserInfo() != null || base.getRawQuery() != null || base.getRawFragment() != null) {
                throw invalid("Base URI requires HTTP(S), a host, and no user-info, query or fragment");
            }
            if (template.startsWith("//") || template.contains(":") || template.contains("?") || template.contains("#")) {
                throw invalid("Route must be a relative path without query or fragment");
            }
        }
        public Builder path(String name, Object value) {
            String text = Objects.requireNonNull(value).toString();
            if (text.isEmpty() || text.equals(".") || text.equals("..")) throw invalid("Path segment must not be empty, dot or dot-dot");
            if (paths.putIfAbsent(name, encode(text)) != null) throw invalid("Duplicate path binding");
            return this;
        }
        public Builder query(String name, Optional<?> value) {
            Objects.requireNonNull(value).ifPresent(v -> {
                if (query.putIfAbsent(name, v.toString()) != null) throw invalid("Duplicate query binding");
            });
            return this;
        }
        public Builder header(String name, Optional<?> value) {
            Objects.requireNonNull(value).ifPresent(v -> {
                if (headers.putIfAbsent(name, v.toString()) != null) throw invalid("Duplicate header binding");
            });
            return this;
        }
        public HttpRequest build(String method) { return build(method, HttpRequest.BodyPublishers.noBody()); }
        public HttpRequest build(String method, byte[] body) {
            headers.putIfAbsent("Content-Type", "application/json");
            return build(method, HttpRequest.BodyPublishers.ofByteArray(Objects.requireNonNull(body)));
        }
        private HttpRequest build(String method, HttpRequest.BodyPublisher body) {
            StringBuilder path = new StringBuilder();
            var matcher = SLOT.matcher(template);
            Set<String> used = new HashSet<>();
            while (matcher.find()) {
                String name = matcher.group(1);
                String value = paths.get(name);
                if (value == null) throw invalid("Route has an unbound path placeholder");
                used.add(name);
                matcher.appendReplacement(path, java.util.regex.Matcher.quoteReplacement(value));
            }
            matcher.appendTail(path);
            if (!used.equals(paths.keySet()) || path.indexOf("{") >= 0 || path.indexOf("}") >= 0) throw invalid("Invalid route placeholders");
            String prefix = base.toASCIIString();
            while (prefix.endsWith("/")) prefix = prefix.substring(0, prefix.length() - 1);
            String suffix = path.toString();
            if (suffix.startsWith("/")) suffix = suffix.substring(1);
            String url = prefix + "/" + suffix;
            if (!query.isEmpty()) url += "?" + String.join("&", query.entrySet().stream()
                    .map(e -> encode(e.getKey()) + "=" + encode(e.getValue())).toList());
            try {
                var request = HttpRequest.newBuilder(URI.create(url));
                headers.forEach(request::header);
                return request.method(method, body).build();
            } catch (IllegalArgumentException ex) {
                // URI/header exception messages can contain tokens; retain no raw value.
                throw invalid("Invalid HTTP route, header or method value");
            }
        }
        private static String encode(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
        }
        private static DtoContractException invalid(String message) { return new DtoContractException("$request", message); }
    }
}
