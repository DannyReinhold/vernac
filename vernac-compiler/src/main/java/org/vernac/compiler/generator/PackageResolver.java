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
}