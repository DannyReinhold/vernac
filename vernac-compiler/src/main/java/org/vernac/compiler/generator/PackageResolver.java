package org.vernac.compiler.generator;

import org.vernac.compiler.ast.CollectionDefinitionNode;

import java.util.Optional;

public final class PackageResolver {

    private PackageResolver() {
        // Utility class
    }

    /**
     * Ermittelt das Package für alle reinen Domänen-Klassen
     * (Value Objects, Entities, Aggregates, Events, Repository-Interfaces).
     */
    public static String resolveDomainPackage(String basePackage, Optional<String> customPackage) {
        if (customPackage != null && customPackage.isPresent() && !customPackage.get().isBlank()) {
            return customPackage.get();
        }
        if (basePackage == null || basePackage.isBlank()) {
            return "domain";
        }
        return basePackage.endsWith(".domain") ? basePackage : basePackage + ".domain";
    }

    /**
     * Ermittelt das Package für Infrastruktur- und Adapter-Klassen
     * (z.B. JdbcRepository Implementierungen).
     */
    public static String resolveAdapterPackage(String basePackage, Optional<String> customPackage) {
        return customPackage.orElseGet(() -> {
            if (basePackage == null || basePackage.isBlank()) {
                return "adapter.db";
            }
            return basePackage + ".adapter.db";
        });
    }

    /**
     * Ermittelt das Package für Outbound Adapter (REST, Custom Delegates, DTOs).
     *
     * @param basePackage          Das Basis-Package der Compilation Unit
     * @param portName             Der Name des Ports, für den der Adapter generiert wird
     * @param customAdapterPackage Optionaler Package-Override direkt aus dem adapter-Block
     */
    public static String resolveOutboundAdapterPackage(String basePackage, String portName, Optional<String> customAdapterPackage) {
        return customAdapterPackage.orElseGet(() -> {
            String base = (basePackage == null || basePackage.isBlank()) ? "" : basePackage + ".";
            return base + "infrastructure.outbound." + portName.toLowerCase();
        });
    }

    public static String resolveCollectionPackage(
            String basePackage,
            Optional<CollectionDefinitionNode> collectionDef,
            Optional<String> parentCustomPackage
    ) {
        // 1. Priorität: Eigenes Package im collection-Block
        if (collectionDef != null && collectionDef.isPresent()) {
            Optional<String> collPkg = collectionDef.get().customPackage();
            if (collPkg.isPresent() && !collPkg.get().isBlank()) {
                return collPkg.get();
            }
        }

        // 2. Priorität: Package der umschließenden Entity / des Value Objects
        if (parentCustomPackage != null && parentCustomPackage.isPresent() && !parentCustomPackage.get().isBlank()) {
            return parentCustomPackage.get();
        }

        // 3. Priorität: Domain-Package aus basePackage ableiten
        return resolveDomainPackage(basePackage, Optional.empty());
    }
}