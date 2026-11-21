// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.modules;

import org.vernac.compiler.ast.AggregateNode;
import org.vernac.compiler.ast.TopLevelDefinition;

/** Tenant scope and entity restoration are versioned module contracts. */
public final class ModuleTenancy {
    private ModuleTenancy() { }

    public static String aggregateScope(TopLevelDefinition definition) {
        if (!(definition instanceof AggregateNode aggregate)) return "NONE";
        return aggregate.tenant() ? "TENANT" : "GLOBAL";
    }

    public static void validateDefinition(ModuleDescriptor.Definition definition) {
        if (!definition.kind().equals("ENTITY")) return;
        boolean restorer = definition.javaMembers().keySet().stream()
                .anyMatch(key -> key.startsWith("__vernacRestore(")
                        && key.endsWith(",org.vernac.runtime.tenancy.TenantOwnership)"));
        if (definition.entityOwnershipVersion() != 1 || !restorer
                || !definition.javaMembers().containsKey("__vernacTenantOwnership()"))
            throw new IllegalArgumentException("Missing entity ownership/restoration contract: "
                    + definition.identity() + ". Rebuild the producer with the current compiler.");
    }

}
