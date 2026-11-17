// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.local;

import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.StreamReadFeature;

/** Private development state, shared by IDE, Maven and local operator tools. Never a production key source. */
final class LocalState {
    static final JsonMapper JSON=JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).build();
    private final Path directory;
    private final Map<String,String> values;
    private LocalState(Path directory,Map<String,String> values) {this.directory=directory;this.values=Map.copyOf(values);}
    static LocalState prepare(Path root) {
        try {
            Path local=root.resolve(".local");
            if(Files.isSymbolicLink(local))throw new IllegalStateException();
            Files.createDirectories(local);privatePermissions(local,"rwx------");
            Path lock=local.resolve("startup.lock");if(Files.isSymbolicLink(lock))throw new IllegalStateException();
            try(var channel=FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE);var ignored=channel.lock()) {
                privatePermissions(lock,"rw-------");
                Path settings=local.resolve("settings.json");if(Files.isSymbolicLink(settings))throw new IllegalStateException();
                Map<String,String> values=new TreeMap<>();
                if(Files.exists(settings)) {
                    if(Files.size(settings)>4096)throw new IllegalStateException();
                    var node=JSON.readTree(Files.readString(settings));
                    if(!node.isObject())throw new IllegalStateException();
                    for(String key:node.propertyNames()) {
                        if(!Set.of("database","runtime","store","preview","merchant","admission","delivery").contains(key)||!node.get(key).isString())throw new IllegalStateException();
                        values.put(key,node.get(key).stringValue());
                    }
                    // Existing Python-launcher state is retained, including all data encryption keys.
                    if(!values.keySet().containsAll(Set.of("database","runtime","store","preview","admission","delivery")))throw new IllegalStateException();
                } else for(String key:List.of("database","runtime","store","preview","admission","delivery"))values.put(key,secret(key));
                values.computeIfAbsent("merchant",key->secret(key));
                for(var entry:values.entrySet()) {
                    if(Set.of("admission","delivery").contains(entry.getKey())) {
                        if(Base64.getDecoder().decode(entry.getValue()).length!=32)throw new IllegalStateException();
                    } else if(!entry.getValue().matches("[A-Za-z0-9_-]{32,64}"))throw new IllegalStateException();
                }
                writePrivate(settings,JSON.writeValueAsString(values)+"\n");
                var state=new LocalState(local,values);state.certificate();return state;
            }
        }catch(Exception unavailable){throw new IllegalStateException("Local development state unavailable; retain .local and inspect its permissions.");}
    }
    static LocalState read(Path root) {
        // Local commands share the exact same validation and persistent secrets as startup.
        if(!Files.isRegularFile(root.resolve(".local/settings.json")))throw new IllegalStateException("Start the local application first.");
        return prepare(root);
    }
    String value(String key) {return Objects.requireNonNull(values.get(key));}
    Path store() {return directory.resolve("localhost.p12").toAbsolutePath();}
    Path composeEnvironment(int port) throws Exception {
        Path path=directory.resolve("compose.env");
        writePrivate(path,"LOCAL_DATABASE_PASSWORD="+value("database")+"\nLOCAL_DB_PORT="+port+"\n");return path.toAbsolutePath();
    }
    private void certificate() throws Exception {
        if(Files.isSymbolicLink(store()))throw new IllegalStateException();
        if(Files.exists(store())) {
            var keys=KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(store())){keys.load(input,value("store").toCharArray());}
            if(!keys.isKeyEntry("local"))throw new IllegalStateException();return;
        }
        Path password=directory.resolve("store-password");writePrivate(password,value("store"));
        Path pending=directory.resolve("localhost-pending.p12");Files.deleteIfExists(pending);
        String executable=System.getProperty("os.name").startsWith("Windows")?"keytool.exe":"keytool";
        var command=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",executable).toString(),
            "-genkeypair","-alias","local","-keyalg","RSA","-keysize","2048","-validity","365","-storetype","PKCS12",
            "-keystore",pending.toString(),"-storepass:file",password.toString(),"-dname","CN=localhost","-ext","SAN=dns:localhost,ip:127.0.0.1")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if(!command.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)){command.destroyForcibly();throw new IllegalStateException();}
            if(command.exitValue()!=0)throw new IllegalStateException();
            privatePermissions(pending,"rw-------");Files.move(pending,store(),StandardCopyOption.ATOMIC_MOVE);
        }finally {Files.deleteIfExists(password);Files.deleteIfExists(pending);}
    }
    private static String secret(String key) {
        byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
        return Set.of("admission","delivery").contains(key)?Base64.getEncoder().encodeToString(bytes):Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    static void writePrivate(Path target,String text) throws Exception {
        if(Files.isSymbolicLink(target))throw new IllegalStateException();
        Path pending=Files.createTempFile(target.getParent(),"local-",".tmp");
        try {privatePermissions(pending,"rw-------");Files.writeString(pending,text);Files.move(pending,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        finally{Files.deleteIfExists(pending);}
    }
    private static void privatePermissions(Path path,String mode) throws Exception {
        if(Files.getFileStore(path).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(mode));
    }
    @Override public String toString(){return "LocalState[REDACTED]";}
}
