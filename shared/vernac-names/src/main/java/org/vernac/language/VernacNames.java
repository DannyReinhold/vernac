// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.language;

import java.util.Locale;
import java.util.Set;

/** Shared, locale-independent name policy for the compiler and the IntelliJ plugin. */
public final class VernacNames {
    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "true", "false", "null", "_");
    private static final Set<String> RESTRICTED_TYPES = Set.of("var", "yield", "record", "sealed", "permits");
    private VernacNames() { }

    public static boolean isStart(int codePoint) { return contains(Unicode15Identifiers.START, codePoint); }
    public static boolean isPart(int codePoint) { return contains(Unicode15Identifiers.PART, codePoint); }

    /** Captured by the lexer so a name containing an invisible character gets a targeted diagnostic. */
    public static boolean isForbidden(int codePoint) {
        return codePoint >= 0 && (Character.isIdentifierIgnorable(codePoint)
                || Character.getType(codePoint) == Character.FORMAT
                || Character.getType(codePoint) == Character.CONTROL);
    }

    /** Java 21 identifier spelling, excluding Java keywords/literals and the single underscore. */
    public static boolean isIdentifier(String name) {
        if (name == null || name.isEmpty() || KEYWORDS.contains(name)) return false;
        int first = name.codePointAt(0);
        if (!isStart(first)) return false;
        for (int offset = Character.charCount(first); offset < name.length();) {
            int point = name.codePointAt(offset);
            if (!isPart(point)) return false;
            offset += Character.charCount(point);
        }
        return true;
    }

    public static boolean isTypeName(String name) {
        return isIdentifier(name) && !RESTRICTED_TYPES.contains(name);
    }

    public static boolean isNamespace(String name) {
        if (name == null || name.isEmpty()) return false;
        for (String segment : name.split("\\.", -1)) if (!isIdentifier(segment)) return false;
        return true;
    }

    public static String lowerFirst(String name) {
        if (name == null || name.isEmpty()) return name;
        int point = name.codePointAt(0);
        return new String(Character.toChars(Character.toLowerCase(point))) + name.substring(Character.charCount(point));
    }

    public static String upperFirst(String name) {
        if (name == null || name.isEmpty()) return name;
        int point = name.codePointAt(0);
        return new String(Character.toChars(Character.toUpperCase(point))) + name.substring(Character.charCount(point));
    }

    public static String invalidNamespaceMessage(String namespace) {
        if (namespace == null || namespace.isEmpty()) return "A namespace must contain at least one segment.";
        for (String segment : namespace.split("\\.", -1))
            if (!isIdentifier(segment)) return invalidNameMessage(segment);
        return "Invalid namespace: " + namespace;
    }

    public static String invalidNameMessage(String name) {
        if (name != null) {
            for (int point : name.codePoints().toArray()) {
                if (isForbidden(point)) return "Names must not contain control, format or identifier-ignorable characters ("
                        + codePoint(point) + "). Remove the character.";
                if (!isPart(point)) return "Invalid identifier character " + codePoint(point)
                        + ". Use the Java 21 identifier alphabet.";
            }
        }
        return "Invalid Java 21 name '" + name + "'. Use a valid identifier; Java keywords and literals are reserved.";
    }

    public static String codePoint(int point) { return String.format(Locale.ROOT, "U+%04X", point); }

    private static boolean contains(int[] ranges, int point) {
        int low = 0, high = ranges.length / 2 - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (point < ranges[middle * 2]) high = middle - 1;
            else if (point > ranges[middle * 2 + 1]) low = middle + 1;
            else return true;
        }
        return false;
    }
}
