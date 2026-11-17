// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.local;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
class LocalStateTest {
    @TempDir Path root;
    @Test void restartsKeepKeysCertificateAndSeparateDatabaseCredentials() throws Exception {
        var first=LocalState.prepare(root);byte[] certificate=Files.readAllBytes(first.store());
        var again=LocalState.prepare(root);
        for(String key:List.of("database","runtime","store","preview","merchant","admission","delivery"))assertEquals(first.value(key),again.value(key));
        assertArrayEquals(certificate,Files.readAllBytes(again.store()));assertNotEquals(first.value("database"),first.value("runtime"));
        assertEquals(32,Base64.getDecoder().decode(first.value("delivery")).length);
        assertFalse(first.toString().contains(first.value("delivery")));
        if(Files.getFileStore(root).supportsFileAttributeView("posix"))assertEquals("rw-------",java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve(".local/settings.json"))));
    }
    @Test void existingPythonStateRetainsEveryEncryptionKeyAndAddsOnlyMissingOperatorCapability() throws Exception {
        var original=LocalState.prepare(root);var json=LocalState.JSON.readTree(Files.readString(root.resolve(".local/settings.json")));
        ((tools.jackson.databind.node.ObjectNode)json).remove("merchant");Files.writeString(root.resolve(".local/settings.json"),json.toString());
        var migrated=LocalState.prepare(root);
        for(String key:List.of("database","runtime","store","preview","admission","delivery"))assertEquals(original.value(key),migrated.value(key));
        assertEquals(43,migrated.value("merchant").length());
    }
    @Test void explicitSettingsHavePriorityAndLocalDefaultsRequireLoopbackHttps() {
        var env=new MockEnvironment().withProperty("server.address","127.0.0.1").withProperty("server.ssl.enabled","true").withProperty("spring.datasource.password","explicit-runtime");
        LocalEnvironment.configure(env,root);
        assertEquals("explicit-runtime",env.getProperty("spring.datasource.password"));
        assertEquals("workspace_runtime",env.getProperty("spring.datasource.username"));
        assertEquals("vernac",env.getProperty("spring.flyway.user"));
        assertThrows(IllegalStateException.class,()->LocalEnvironment.configure(new MockEnvironment().withProperty("server.address","0.0.0.0").withProperty("server.ssl.enabled","true"),root));
    }
    @Test void damagedStateNeverSilentlyRotatesKeys() throws Exception {
        LocalState.prepare(root);var file=root.resolve(".local/settings.json");Files.writeString(file,"{\"database\":\"broken\"}");
        assertThrows(IllegalStateException.class,()->LocalState.prepare(root));assertEquals("{\"database\":\"broken\"}",Files.readString(file));
    }
}
