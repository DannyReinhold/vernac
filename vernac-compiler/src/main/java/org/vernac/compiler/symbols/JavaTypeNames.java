// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.symbols;

/** Reviewed Java target identity shared by generation and API validation. */
public final class JavaTypeNames {
    private JavaTypeNames() { }

    public static String domainPackage(TypeIdentity identity) {
        return identity.namespace() + ".domain";
    }

    /** Erased parameter name for the currently supported non-generic resolved types. */
    public static String canonicalName(ResolvedType type) {
        return switch (type) {
            case ResolvedType.Builtin builtin -> builtin.javaType().getName();
            case ResolvedType.VoidReturn ignored -> "void";
            case ResolvedType.Page page -> "org.vernac.runtime.pagination."+page.kind().javaName()+"<"+canonicalName(page.element())+", "+canonicalName(page.collection())+">";
            case ResolvedType.Declared declared -> switch (declared.symbol().kind()) {
                case EVENT, ID, VALUE_OBJECT, ENUM, COLLECTION, ENTITY, AGGREGATE, DOMAIN_SERVICE, REPOSITORY, PORT -> domainPackage(declared.symbol().identity())
                        + "." + declared.symbol().identity().name();
                case INBOUND_HTTP_ADAPTER -> declared.symbol().identity().namespace()+".adapter.inbound.http."+declared.symbol().identity().name();
                case OUTBOUND_ADAPTER -> declared.symbol().identity().namespace()+".adapter.outbound."+declared.symbol().identity().name();
                case LISTENER -> declared.symbol().identity().namespace()+".adapter.inbound.event."+declared.symbol().identity().name();
                case USE_CASE -> declared.symbol().identity().namespace()+".usecase."+declared.symbol().identity().name();
                default -> throw new IllegalArgumentException("No reviewed Java mapping for " + declared.symbol().kind());
            };
        };
    }
}
