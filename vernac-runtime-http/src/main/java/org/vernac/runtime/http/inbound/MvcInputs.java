// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.http.inbound;

import jakarta.servlet.http.HttpServletRequest;

import org.jspecify.annotations.*;
import org.springframework.web.servlet.HandlerMapping;

import java.util.*;

/** Declared scalar values only. Never exposes the servlet request, headers or sensitive fields. */
@NullMarked
public final class MvcInputs {
    public record Binding(String kind, String name, String externalName, boolean sensitive) {}

    private final Map<String, String> values;
    private final Map<String, String> publicValues;
    private final Set<String> duplicates;

    private MvcInputs(
            Map<String, String> values, Map<String, String> publicValues, Set<String> duplicates) {
        this.values = Map.copyOf(values);
        this.publicValues = Map.copyOf(publicValues);
        this.duplicates = Set.copyOf(duplicates);
    }

    public static MvcInputs empty() {
        return new MvcInputs(Map.of(), Map.of(), Set.of());
    }

    public static MvcInputs capture(HttpServletRequest request, List<Binding> bindings) {
        Map<String, String> values = new LinkedHashMap<>(), publicValues = new LinkedHashMap<>();
        Set<String> duplicates = new HashSet<>();
        Object paths = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        for (var binding : bindings) {
            List<String> candidates;
            if (binding.kind().equals("path")) {
                Object value =
                        paths instanceof Map<?, ?> map ? map.get(binding.externalName()) : null;
                candidates = value instanceof String text ? List.of(text) : List.of();
            } else if (binding.kind().equals("header")) {
                candidates = Collections.list(request.getHeaders(binding.externalName()));
            } else {
                var parameters = request.getParameterValues(binding.externalName());
                candidates = parameters == null ? List.of() : Arrays.asList(parameters);
            }
            if (candidates.size() > 1) {
                duplicates.add(normalize(binding.kind() + "." + binding.externalName()));
                continue;
            }
            if (candidates.isEmpty()) continue;
            String value = candidates.getFirst();
            values.put(normalize(binding.kind() + "." + binding.externalName()), value);
            if (!binding.sensitive() && !binding.kind().equals("header"))
                publicValues.put(binding.kind() + "." + binding.name(), value);
        }
        return new MvcInputs(values, publicValues, duplicates);
    }

    /** A page loader validates only its declared inputs, never the failed form's other fields. */
    public MvcInputs forLoader(List<Binding> bindings) {
        Map<String, String> selected = new LinkedHashMap<>();
        Set<String> duplicateKeys = new HashSet<>();
        for (var binding : bindings) {
            String key = normalize(binding.kind() + "." + binding.externalName());
            String value = values.get(normalize(key));
            if (value != null) selected.put(key, value);
            if (duplicates.contains(key)) duplicateKeys.add(key);
        }
        return new MvcInputs(selected, Map.of(), duplicateKeys);
    }

    public void check() {
        if (!duplicates.isEmpty()) throw new WireValueException("duplicate scalar input");
    }

    public String required(String key, @Nullable String defaultValue) {
        String value = values.get(normalize(key));
        if (value != null) return value;
        if (defaultValue != null) return defaultValue;
        throw new WireValueException("required input " + key);
    }

    public Optional<String> optional(String key) {
        return Optional.ofNullable(values.get(normalize(key)));
    }

    private static String normalize(String key) {
        return key.startsWith("header.")
                ? "header." + key.substring(7).toLowerCase(Locale.ROOT)
                : key;
    }

    public Map<String, String> publicValues() {
        return publicValues;
    }

    @Override
    public String toString() {
        return "MvcInputs[declared=" + values.size() + ", duplicate=" + duplicates.size() + "]";
    }
}
