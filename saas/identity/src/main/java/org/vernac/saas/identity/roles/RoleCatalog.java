// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.roles;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import org.vernac.runtime.authorization.PermissionKey;

/** Application-owned immutable catalog. No classpath discovery, authority strings or implicit owner grants. */
public final class RoleCatalog {
    public static final int MAX_BYTES=65_536, MAX_ROLES=128, MAX_PERMISSIONS=128, MAX_ASSIGNMENTS=32;
    private final Map<String,Set<PermissionKey>> roles;
    private final String fingerprint;
    private RoleCatalog(Map<String,Set<PermissionKey>> roles) {
        this.roles=Collections.unmodifiableMap(new TreeMap<>(roles));
        StringBuilder canonical=new StringBuilder("vernac.roles.v1\n");
        this.roles.forEach((role,keys)->{canonical.append(role).append('\n');
            keys.stream().map(PermissionKey::qualifiedName).sorted().forEach(key->canonical.append(key).append('\n'));});
        try { fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException unavailable) { throw invalid(); }
    }
    public static RoleCatalog fromClasspath(ClassLoader loader,String resource,Collection<? extends PermissionKey> vocabulary) {
        if(loader==null || resource==null || resource.length()>256 || !resource.matches("[A-Za-z0-9_/-]+\\.json")
                || resource.startsWith("/") || Arrays.asList(resource.split("/")).contains(".."))throw invalid();
        try(var input=loader.getResourceAsStream(resource)) { return read(input,vocabulary); }
        catch(IOException | RuntimeException unavailable) { throw invalid(); }
    }
    public static RoleCatalog read(InputStream input,Collection<? extends PermissionKey> vocabulary) {
        try {
            if(input==null || vocabulary==null || vocabulary.size()>2048)throw invalid();
            Map<String,PermissionKey> known=new HashMap<>();
            for(PermissionKey key:vocabulary) {
                // Explicit generated/application enum declarations only; PermissionKey.of or Spring strings are not vocabulary discovery.
                if(!(key instanceof Enum<?>) || !qualified(key.qualifiedName()) || known.putIfAbsent(key.qualifiedName(),PermissionKey.of(key.qualifiedName()))!=null)
                    throw invalid();
            }
            byte[] bytes=input.readNBytes(MAX_BYTES+1);if(bytes.length>MAX_BYTES)throw invalid();
            var factory=JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(512).maxNumberLength(8).build()).build();
            var mapper=new ObjectMapper(factory).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            JsonNode root=mapper.readTree(bytes);fields(root,Set.of("format","roles"));
            if(!root.path("format").isInt() || root.path("format").intValue()!=1 || !root.path("roles").isArray()
                    || root.path("roles").size()>MAX_ROLES)throw invalid();
            Map<String,Set<PermissionKey>> roles=new HashMap<>();
            for(JsonNode row:root.path("roles")) {
                fields(row,Set.of("key","permissions"));
                if(!row.path("key").isTextual() || !qualified(row.path("key").textValue()) || !row.path("permissions").isArray()
                        || row.path("permissions").size()>MAX_PERMISSIONS)throw invalid();
                Set<PermissionKey> permissions=new HashSet<>();
                for(JsonNode permission:row.path("permissions")) {
                    PermissionKey key=permission.isTextual()?known.get(permission.textValue()):null;
                    if(key==null || !permissions.add(key))throw invalid();
                }
                if(roles.putIfAbsent(row.path("key").textValue(),Set.copyOf(permissions))!=null)throw invalid();
            }
            return new RoleCatalog(roles);
        } catch(IOException | RuntimeException unavailable) { throw invalid(); }
    }
    private static void fields(JsonNode node,Set<String> expected) {
        if(node==null || !node.isObject() || node.size()!=expected.size())throw invalid();
        node.fieldNames().forEachRemaining(name->{if(!expected.contains(name))throw invalid();});
    }
    public static boolean qualified(String key) {
        return key!=null && key.length()<=256 && key.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*){1,15}");
    }
    /** Canonical finite assignment representation; unknown/removed roles never disappear silently. */
    public SortedSet<String> assignments(Collection<String> selected) {
        if(selected==null || selected.size()>MAX_ASSIGNMENTS)throw invalid();
        SortedSet<String> copy=new TreeSet<>();
        for(String role:selected)if(!roles.containsKey(role) || !copy.add(role))throw invalid();
        return Collections.unmodifiableSortedSet(copy);
    }
    public Set<PermissionKey> permissions(Collection<String> selected) {
        Set<PermissionKey> result=new HashSet<>();assignments(selected).forEach(role->result.addAll(roles.get(role)));return Set.copyOf(result);
    }
    public Set<String> roleKeys() { return roles.keySet(); }
    public String fingerprint() { return fingerprint; }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid Identity role catalog"); }
    @Override public String toString() { return "RoleCatalog[configured]"; }
}
