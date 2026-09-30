package org.vernac.compiler.generator;

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
        return customPackage.orElseGet(() -> {
            if (basePackage == null || basePackage.isBlank()) {
                return "domain";
            }
            return basePackage + ".domain";
        });
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
}