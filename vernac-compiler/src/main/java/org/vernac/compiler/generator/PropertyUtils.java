package org.vernac.compiler.generator;

import java.util.Locale;

public class PropertyUtils {
    private PropertyUtils() {
    }

    public static String resolvePropertyName(String name) {
        return toKebabCase(name);
    }

    private static String toKebabCase(String name) {
        if (name == null || name.isBlank()) return "";
        return name.replaceAll("([a-z])([A-Z]+)", "$1-$2").toLowerCase(Locale.ROOT);
    }
}
