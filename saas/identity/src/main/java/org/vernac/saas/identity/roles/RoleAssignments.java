// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.roles;
import java.util.*;
/** Canonical bounded persisted role-key set; catalog membership is checked separately at policy/startup. */
public final class RoleAssignments {
    private RoleAssignments() {}
    public static String encode(Collection<String> keys) {
        if(keys==null || keys.size()>RoleCatalog.MAX_ASSIGNMENTS)throw invalid();
        var sorted=new TreeSet<String>();for(String key:keys)if(!RoleCatalog.qualified(key) || !sorted.add(key))throw invalid();
        return String.join(",",sorted);
    }
    public static Set<String> decode(String text) {
        if(text==null || text.length()>RoleCatalog.MAX_ASSIGNMENTS*257)throw invalid();
        var keys=text.isEmpty()?List.<String>of():Arrays.asList(text.split(",",-1));
        if(!encode(keys).equals(text))throw invalid();return Set.copyOf(keys);
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid Identity role assignments");}
}
