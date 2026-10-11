// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.util;

import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.ast.FieldNode;
import java.util.*;

/** Shared duplicate-name diagnostics for legacy single-file and project analysis. */
public final class MemberNames {
    private MemberNames() { }

    public static String describe(FieldNode field, String kind) {
        return field.hasExplicitName() ? kind + " '" + field.name() + "'"
                : kind + " '" + field.name() + "' derived from type '" + field.type().name() + "'";
    }

    public static List<CompilerDiagnostic> duplicates(List<FieldNode> fields, String kind, String context) {
        List<CompilerDiagnostic> result = new ArrayList<>();
        Map<String, FieldNode> names = new LinkedHashMap<>();
        for (FieldNode field : fields) {
            FieldNode previous = names.putIfAbsent(field.name(), field);
            if (previous != null) result.add(CompilerDiagnostic.error(field.location(),
                    "Duplicate " + kind + " name '" + field.name() + "'" + (context.isEmpty() ? "" : " in '" + context + "'")
                            + ": " + describe(field, kind) + " conflicts with " + describe(previous, kind)
                            + " at " + previous.location() + ". Specify distinct explicit " + kind + " names."));
        }
        return result;
    }
}
