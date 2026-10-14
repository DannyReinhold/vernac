// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.jdbc.modules;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.sql.DataSource;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.util.*;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.vernac.runtime.application.AdapterBindings;
import org.vernac.runtime.persistence.*;

/** One coordinator: verify all inputs, migrate dependencies first, then the application. */
public final class ModuleMigrationStrategy implements FlywayMigrationStrategy,BeanClassLoaderAware {
    private static final ObjectMapper JSON=new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(SerializationFeature.INDENT_OUTPUT).setDefaultPrettyPrinter(new DefaultPrettyPrinter().withObjectIndenter(new DefaultIndenter("  ","\n")));
    private final AdapterBindings bindings;
    private final String applicationSchema;
    private final DataSource dataSource;
    private ClassLoader loader=ModuleMigrationStrategy.class.getClassLoader();
    public ModuleMigrationStrategy(AdapterBindings bindings,String applicationSchema,DataSource dataSource){this.bindings=bindings;this.applicationSchema=applicationSchema;this.dataSource=dataSource;}
    @Override public void setBeanClassLoader(ClassLoader loader){this.loader=loader;}
    public record Prepared(String module,String location,String historySchema,SchemaDescription schema,Set<String> owned) { }
    /** Testable orchestration; executing/validating SQL stays in the JDBC adapter below. */
    public interface Operations {
        void migrate(Prepared module);
        void validate(Prepared module);
        void migrateApplication();
        void validateApplication(SchemaDescription schema,Set<String> owned);
    }
    public static void coordinate(List<Prepared> modules,SchemaDescription application,Set<String> owned,Operations operations) {
        Map<String,String> owners=new TreeMap<>();
        for(var module:modules)for(String table:module.owned())claim(owners,table,module.module());
        for(String table:owned)claim(owners,table,"@application");
        for(var module:modules){operations.migrate(module);operations.validate(module);}
        operations.migrateApplication();operations.validateApplication(application,owned);
    }
    private static void claim(Map<String,String> owners,String table,String owner){String previous=owners.putIfAbsent(table,owner);if(previous!=null)throw new IllegalStateException("Schema ownership collision: "+table+" belongs to "+previous+" and "+owner);}
    @Override public void migrate(Flyway application) {
        if(unwrap(application.getConfiguration().getDataSource())!=unwrap(dataSource))throw new IllegalStateException("Persistent modules require the application Flyway and JDBC infrastructure to share one DataSource. A separate spring.flyway.url/DataSource is not supported yet.");
        try {
            var node=JSON.readTree(resource(applicationSchema));
            var app=JSON.treeToValue(node.get("schema"),SchemaDescription.class);
            Set<String> appOwned=new TreeSet<>();node.path("ownedTables").forEach(t->appOwned.add(t.asText()));
            List<Prepared> modules=new ArrayList<>();
            for(String module:bindings.persistentModules())modules.add(prepare(module));
            if(Arrays.stream(application.info().all()).anyMatch(m->m.getState().name().startsWith("FUTURE")))throw new IllegalStateException("Application database contains migration versions newer than this artifact. Refusing persistent module startup.");
            for(var location:application.getConfiguration().getLocations()) {
                String path=location.getDescriptor();
                if(path.startsWith("classpath:")) {
                    String prefix=path.substring("classpath:".length()).replaceFirst("^/+","").replaceFirst("[*?].*$","");
                    if(modules.stream().anyMatch(m->m.location().substring("classpath:".length()).startsWith(prefix)))throw new IllegalStateException("Application Flyway locations overlap isolated module resources. Keep module migration locations owned by the coordinator.");
                }
            }
            coordinate(modules,app,appOwned,new Operations(){
                public void migrate(Prepared module){Flyway.configure(loader).dataSource(dataSource).locations(module.location()).schemas(module.historySchema()).defaultSchema(module.historySchema()).table("flyway_schema_history").validateMigrationNaming(true).ignoreMigrationPatterns(new String[0]).load().migrate();}
                public void validate(Prepared module){JdbcModuleSchemaValidator.validate(dataSource,module.schema(),module.owned(),module.module());}
                public void migrateApplication(){application.migrate();}
                public void validateApplication(SchemaDescription schema,Set<String> owned){JdbcModuleSchemaValidator.validate(dataSource,schema,owned,"@application");}
            });
        }catch(IOException e){throw new IllegalStateException("Invalid/missing module migration or application schema resources. Rebuild the application and its dependency artifacts.",e);}
    }
    private byte[] resource(String name) throws IOException {
        var urls=Collections.list(loader.getResources(name));if(urls.size()!=1)throw new IOException("Expected one artifact resource: "+name);
        try(var in=urls.getFirst().openStream()){return in.readAllBytes();}
    }
    private Prepared prepare(String module) throws IOException {
        String prefix="META-INF/vernac/modules/"+module+"/";
        var manifest=JSON.readTree(resource(prefix+"persistence.json"));
        String history="vernac_module_"+hash(module.getBytes(StandardCharsets.UTF_8)).substring(0,32);
        if(manifest.path("formatVersion").asInt()!=1||!manifest.path("module").asText().equals(module)||!manifest.path("migrationLocation").asText().equals("classpath:"+prefix+"migration")||!manifest.path("historySchema").asText().equals(history)||!manifest.path("historyTable").asText().equals("flyway_schema_history"))throw new IOException("Invalid module persistence identity: "+module);
        var expected=JSON.treeToValue(manifest.get("expectedSchema"),SchemaDescription.class);
        if(!fingerprint(expected).equals(manifest.path("schemaFingerprint").asText()))throw new IOException("Invalid module schema fingerprint: "+module);
        SchemaDescription current=new SchemaDescription(1,1,List.of(),Map.of());Set<String> owned=new TreeSet<>(),versions=new HashSet<>(),inventory=new TreeSet<>();inventory.add(prefix+"persistence.json");String previous="";
        for(var migration:manifest.path("migrations")) {
            String file=migration.path("file").asText();
            if(!file.matches("V[0-9]{17}__[a-z0-9_]+\\.sql")||previous.compareTo(file)>=0||!versions.add(file.substring(1,18)))throw new IOException("Invalid module migration order: "+module);
            inventory.add(prefix+"migration/"+file);inventory.add(prefix+"history/"+file.replace(".sql",".json"));
            byte[] sql=resource(prefix+"migration/"+file),snapshot=resource(prefix+"history/"+file.replace(".sql",".json"));
            if(!hash(sql).equals(migration.path("sqlSha256").asText())||!hash(snapshot).equals(migration.path("snapshotSha256").asText()))throw new IOException("Changed module migration bytes: "+module+"/"+file);
            var entry=JSON.readTree(snapshot);var model=JSON.treeToValue(entry.get("model"),SchemaDescription.class);
            if(entry.path("formatVersion").asInt()!=1||!entry.path("migration").asText().equals(file)||!entry.path("previousFingerprint").asText().equals(fingerprint(current))||!entry.path("modelFingerprint").asText().equals(fingerprint(model)))throw new IOException("Module snapshot chain diverges: "+module+"/"+file);
            model.tables().forEach(t->owned.add(key(t)));current=model;previous=file;
        }
        if(!fingerprint(current).equals(fingerprint(expected)))throw new IOException("Module history does not reach its expected schema: "+module);
        verifyInventory(prefix,inventory);
        return new Prepared(module,"classpath:"+prefix+"migration",history,expected,Set.copyOf(owned));
    }
    private void verifyInventory(String prefix,Set<String> expected) throws IOException {
        var urls=Collections.list(loader.getResources(prefix+"persistence.json"));if(urls.size()!=1)throw new IOException("Ambiguous module resources: "+prefix);
        var url=urls.getFirst();Set<String> actual=new TreeSet<>();
        if(url.getProtocol().equals("file")) {
            try {
                var directory=java.nio.file.Path.of(url.toURI()).getParent();
                try(var files=java.nio.file.Files.walk(directory)){files.filter(java.nio.file.Files::isRegularFile).forEach(p->actual.add(prefix+directory.relativize(p).toString().replace('\\','/')));}
            }catch(java.net.URISyntaxException e){throw new IOException("Invalid module resource URL",e);}
        }else {
            var connection=url.openConnection();connection.setUseCaches(false);
            if(!(connection instanceof java.net.JarURLConnection jarConnection))throw new IOException("Unsupported module resource layout: "+url.getProtocol());
            try(var jar=jarConnection.getJarFile()){jar.stream().filter(e->!e.isDirectory()&&e.getName().startsWith(prefix)).forEach(e->actual.add(e.getName()));}
        }
        if(!actual.equals(expected))throw new IOException("Unexpected/missing module persistence files: "+prefix);
    }
    public static String fingerprint(SchemaDescription schema) throws IOException {return hash((JSON.writeValueAsString(schema)+"\n").getBytes(StandardCharsets.UTF_8));}
    private static String key(SchemaDescription.Table t){return SqlIdentifiers.physical(t.namespace())+"/"+SqlIdentifiers.physical(t.name());}
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static DataSource unwrap(DataSource source){while(source instanceof DelegatingDataSource proxy&&proxy.getTargetDataSource()!=null)source=proxy.getTargetDataSource();return source;}
}
