// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.modules;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.jspecify.annotations.NullMarked;
import org.vernac.compiler.persistence.*;
import org.vernac.language.VernacNames;

/** Data-only persistence bundle. Packaging does not approve or apply a migration. */
@NullMarked
public final class ModulePersistence {
    private ModulePersistence() { }
    public static final String HISTORY_TABLE="flyway_schema_history";
    public record Migration(String file,String sqlSha256,String snapshotSha256) { }
    public record Descriptor(int formatVersion,String module,String migrationLocation,
            String historySchema,String historyTable,List<Migration> migrations,
            SchemaModel expectedSchema,String schemaFingerprint) {
        public Descriptor {
            migrations=List.copyOf(migrations);
            if(formatVersion!=1)throw new IllegalArgumentException("Unsupported module persistence format");
            validateIdentity(module);
            if(!migrationLocation.equals("classpath:"+directory(module)+"migration") ||
                    !historySchema.equals(ModulePersistence.historySchema(module)) || !historyTable.equals(HISTORY_TABLE))
                throw new IllegalArgumentException("Noncanonical module migration/history identity: "+module);
            if(!expectedSchema.fingerprint().equals(schemaFingerprint))throw new IllegalArgumentException("Module schema fingerprint mismatch: "+module);
            String previous="";Set<String> versions=new HashSet<>();
            for(var migration:migrations) {
                validateFile(migration.file());
                if(previous.compareTo(migration.file())>=0||!versions.add(migration.file().substring(1,18)))throw new IllegalArgumentException("Unordered/duplicate module migration version: "+migration.file());
                if(!migration.sqlSha256().matches("[a-f0-9]{64}")||!migration.snapshotSha256().matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid module migration checksum: "+migration.file());
                previous=migration.file();
            }
            if(migrations.isEmpty()&&!expectedSchema.tables().isEmpty())throw new IllegalArgumentException("Nonempty module schema requires migration history");
        }
    }
    /** Resource names are relative to the artifact root, with no filesystem paths. */
    public record Bundle(Descriptor descriptor,Map<String,byte[]> resources) {
        public Bundle {resources=Collections.unmodifiableMap(new TreeMap<>(resources));}
    }
    public static String directory(String module) {validateIdentity(module);return "META-INF/vernac/modules/"+module+"/";}
    public static String resource(String module) {return directory(module)+"persistence.json";}
    /** Stable, bounded PostgreSQL identifier, independent of Maven coordinates/version. */
    public static String historySchema(String module) {
        validateIdentity(module);return "vernac_module_"+sha256(module.getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0,32);
    }
    private static void validateIdentity(String module) {
        if(!VernacNames.isNamespace(module)||!module.contains("."))throw new IllegalArgumentException("Invalid module identity: "+module);
    }
    private static void validateFile(String file) {
        if(!file.matches("V[0-9]{17}__[a-z0-9_]+\\.sql"))throw new IllegalArgumentException("Unsupported module migration filename: "+file);
    }
    public static String sha256(byte[] bytes) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }
    public static Bundle prepare(String module,SchemaModel expected,Path history,Path sql) throws IOException {
        validateIdentity(module);
        SchemaModel recorded=new SchemaHistory(history,sql).read();
        if(!recorded.fingerprint().equals(expected.fingerprint()))throw new IOException("Module schema differs from its migration history: "+module+". Generate and review the next migration before packaging.");
        requireFlat(history,".json");requireFlat(sql,".sql");
        Map<String,byte[]> resources=new TreeMap<>();List<Migration> migrations=new ArrayList<>();Set<String> paired=new TreeSet<>();
        List<Path> snapshots;
        if(Files.exists(history))try(var files=Files.list(history)){snapshots=files.filter(p->p.getFileName().toString().endsWith(".json")).sorted().toList();}
        else snapshots=List.of();
        for(Path snapshot:snapshots) {
            if(!Files.isRegularFile(snapshot,LinkOption.NOFOLLOW_LINKS))throw new IOException("Module history must contain regular files: "+snapshot.getFileName());
            byte[] snapshotBytes=Files.readAllBytes(snapshot);
            var entry=SchemaModel.JSON.readValue(snapshotBytes,SchemaHistory.Snapshot.class);
            String name=entry.migration();validateFile(name);
            Path migration=sql.resolve(name);
            if(!Files.isRegularFile(migration,LinkOption.NOFOLLOW_LINKS))throw new IOException("Module migration must be a regular file: "+name);
            byte[] bytes=Files.readAllBytes(migration);paired.add(name);
            migrations.add(new Migration(name,sha256(bytes),sha256(snapshotBytes)));
            resources.put(directory(module)+"migration/"+name,bytes);
            resources.put(directory(module)+"history/"+snapshot.getFileName(),snapshotBytes);
        }
        if(Files.exists(sql))try(var files=Files.list(sql)) {
            for(Path file:files.filter(p->p.getFileName().toString().endsWith(".sql")).toList())
                if(!paired.contains(file.getFileName().toString()))throw new IOException("Unpaired module migration: "+file.getFileName()+". Every module SQL migration requires its reviewed schema snapshot; repeatable migrations are not supported here.");
        }
        var descriptor=new Descriptor(1,module,"classpath:"+directory(module)+"migration",historySchema(module),HISTORY_TABLE,migrations,expected,expected.fingerprint());
        resources.put(resource(module),ModuleDescriptor.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(descriptor));
        return new Bundle(descriptor,resources);
    }
    private static void requireFlat(Path directory,String suffix) throws IOException {
        if(!Files.exists(directory))return;
        try(var files=Files.walk(directory)) {
            for(Path path:files.filter(p->p.getFileName().toString().endsWith(suffix)).toList())
                if(!path.getParent().equals(directory)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Module history/migrations must be flat regular files: "+directory.relativize(path));
        }
    }
    /** Verify packaged bytes and the complete snapshot chain without executing SQL. */
    public static void verify(Descriptor descriptor,Map<String,byte[]> resources) throws IOException {
        Set<String> expectedFiles=new HashSet<>();expectedFiles.add(resource(descriptor.module()));
        SchemaModel current=SchemaModel.empty();
        for(var migration:descriptor.migrations()) {
            String sql=directory(descriptor.module())+"migration/"+migration.file();
            String snapshot=directory(descriptor.module())+"history/"+migration.file().replace(".sql",".json");
            expectedFiles.add(sql);expectedFiles.add(snapshot);
            var sqlBytes=resources.get(sql);var snapshotBytes=resources.get(snapshot);
            if(sqlBytes==null||!sha256(sqlBytes).equals(migration.sqlSha256()))throw new IOException("Missing/changed packaged module SQL: "+migration.file());
            if(snapshotBytes==null||!sha256(snapshotBytes).equals(migration.snapshotSha256()))throw new IOException("Missing/changed packaged module snapshot: "+migration.file());
            var entry=SchemaModel.JSON.readValue(snapshotBytes,SchemaHistory.Snapshot.class);
            if(entry.formatVersion()!=1||!entry.migration().equals(migration.file())||!entry.previousFingerprint().equals(current.fingerprint())||!entry.modelFingerprint().equals(entry.model().fingerprint()))throw new IOException("Packaged module schema history diverges at "+migration.file());
            current=entry.model();
        }
        if(!current.fingerprint().equals(descriptor.schemaFingerprint()))throw new IOException("Packaged module history does not reach expected schema: "+descriptor.module());
        var supplied=resources.keySet().stream().filter(p->p.startsWith(directory(descriptor.module()))).collect(java.util.stream.Collectors.toSet());
        if(!supplied.equals(expectedFiles))throw new IOException("Unexpected/missing module persistence resources: "+descriptor.module());
        byte[] manifest=resources.get(resource(descriptor.module()));
        if(manifest==null||!descriptor.equals(ModuleDescriptor.JSON.readValue(manifest,Descriptor.class)))throw new IOException("Packaged module persistence descriptor mismatch: "+descriptor.module());
    }
    /** Reject overlapping physical tables in the expected schemas of participating owners. */
    public static void checkOwnership(List<Descriptor> modules,SchemaModel application) {
        Map<String,String> owners=new TreeMap<>();Set<String> names=new HashSet<>(),histories=new HashSet<>();
        for(var module:modules) {
            if(!names.add(module.module())||!histories.add(module.historySchema()))throw new IllegalArgumentException("Duplicate module/history identity: "+module.module());
            for(var table:module.expectedSchema().tables())claim(owners,table,module.module());
        }
        for(var table:application.tables())claim(owners,table,"@application");
    }
    private static void claim(Map<String,String> owners,SchemaModel.Table table,String owner) {
        String physical=SqlNames.physical(table.namespace())+"/"+SqlNames.physical(table.name());
        String previous=owners.putIfAbsent(physical,owner);
        if(previous!=null)throw new IllegalArgumentException("Persistence ownership collision: "+table.identity()+" belongs to "+previous+" and "+owner);
    }
}
