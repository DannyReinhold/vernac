// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.persistence;
/** Compiler facade for the shared PostgreSQL identifier contract. */
public final class SqlNames {
    private SqlNames() { }
    public static String physical(String name){return org.vernac.runtime.persistence.SqlIdentifiers.physical(name);}
    public static String quote(String name){return org.vernac.runtime.persistence.SqlIdentifiers.quote(name);}
    public static String name(String name){return org.vernac.runtime.persistence.SqlIdentifiers.name(name);}
    public static String table(String namespace,String table){return org.vernac.runtime.persistence.SqlIdentifiers.table(namespace,table);}
    public static String literal(String value){return org.vernac.runtime.persistence.SqlIdentifiers.literal(value);}
    public static String hash(String value){return org.vernac.runtime.persistence.SqlIdentifiers.hash(value);}
}
