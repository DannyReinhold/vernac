// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.util;

import java.util.Map;

public final class TypeUtils {

    private static final Map<String, String> PRIMITIVE_TO_WRAPPER = Map.of(
            "int", "Integer",
            "long", "Long",
            "double", "Double",
            "float", "Float",
            "boolean", "Boolean",
            "byte", "Byte",
            "short", "Short",
            "char", "Character"
    );

    private TypeUtils() {
        // Utility class
    }

    public static boolean isPrimitive(String typeName) {
        return PRIMITIVE_TO_WRAPPER.containsKey(typeName);
    }

    public static String getWrapperType(String primitiveType) {
        return PRIMITIVE_TO_WRAPPER.get(primitiveType);
    }

    public static Iterable<String> getAllPrimitives() {
        return PRIMITIVE_TO_WRAPPER.keySet();
    }
}