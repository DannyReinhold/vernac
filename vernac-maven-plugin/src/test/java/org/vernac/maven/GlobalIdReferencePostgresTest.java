// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.vernac.compiler.persistence.*;
import org.vernac.compiler.pipeline.VernacCompiler;
@EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
class GlobalIdReferencePostgresTest {
    @TempDir Path root;
    @Test void restrictedWritesEnforceReferencesAndReplayDetectsConstraintDrift() throws Exception {
        var source=Files.createDirectories(root.resolve("src/main/vernac/demo")).resolve("model.vernac");
        String model="namespace demo; id ParentId; id ChildId; global aggregate Parent[ParentId](); repository Parents for Parent {} global aggregate Child[ChildId](ParentId parent references Parent); repository Children for Child {}";
        Files.writeString(source,model.replace(" references Parent",""));var compiler=new VernacCompiler();
        var before=new SchemaBuilder(compiler.analyzeProject(root.resolve("src/main/vernac")),SchemaModel.empty()).build();
        Files.writeString(source,model);var schema=new SchemaBuilder(compiler.analyzeProject(root.resolve("src/main/vernac")),before).build();
        String adminUrl=System.getenv("VERNAC_PG_TEST_URL"),user=System.getenv("VERNAC_PG_TEST_USER"),password=System.getenv("VERNAC_PG_TEST_PASSWORD");
        String suffix=UUID.randomUUID().toString().replace("-",""),db="ref_"+suffix,role="ref_runtime_"+suffix,secret=UUID.randomUUID().toString();
        try(var admin=DriverManager.getConnection(adminUrl,user,password);var sql=admin.createStatement()) {
            sql.execute("CREATE DATABASE "+db);sql.execute("CREATE ROLE "+role+" LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '"+secret+"'");
            try {
                String url=SchemaCheckMojo.databaseUrl(adminUrl,db);
                try(var owner=DriverManager.getConnection(url,user,password);var ddl=owner.createStatement()) {
                    ddl.execute(new MigrationPlanner().plan(SchemaModel.empty(),schema,MigrationPlanner.Options.safe()).sql());
                    ddl.execute("GRANT USAGE ON SCHEMA demo TO "+role);ddl.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA demo TO "+role);
                }
                try(var runtime=DriverManager.getConnection(url,role,secret);var write=runtime.createStatement()) {
                    var parent=UUID.randomUUID();var child=UUID.randomUUID();
                    String fields="\"id\",\"@version\",\"@createdAt\",\"@updatedAt\",\"@nanoRemainder:@createdAt\",\"@nanoRemainder:@updatedAt\"";
                    String values="0,now(),now(),0,0";
                    String insert="INSERT INTO demo.\"Child\" ("+fields+",\"parent\") VALUES ('"+child+"',"+values+",'"+parent+"')";
                    assertEquals("23503",assertThrows(SQLException.class,()->write.execute(insert)).getSQLState());
                    write.execute("INSERT INTO demo.\"Parent\" ("+fields+") VALUES ('"+parent+"',"+values+")");write.execute(insert);
                    assertEquals("23503",assertThrows(SQLException.class,()->write.execute("DELETE FROM demo.\"Parent\" WHERE id='"+parent+"'")).getSQLState());
                    assertThrows(SQLException.class,()->write.execute("ALTER TABLE demo.\"Child\" DROP CONSTRAINT \"@fk:reference:parent\""));
                }
            } finally {sql.execute("DROP DATABASE "+db+" WITH (FORCE)");sql.execute("DROP ROLE "+role);}
        }
        var history=root.resolve("history");var migrations=root.resolve("migration");var chain=new SchemaHistory(history,migrations);var planner=new MigrationPlanner();
        chain.append("20261115000100000","initial",SchemaModel.empty(),before,planner.plan(SchemaModel.empty(),before,MigrationPlanner.Options.safe()));
        chain.append("20261115000200000","reference",before,schema,planner.plan(before,schema,MigrationPlanner.Options.safe()));
        var goal=new SchemaCheckMojo();goal.sourceDirectory=root.resolve("src/main/vernac").toFile();goal.historyDirectory=history.toFile();goal.migrationDirectory=migrations.toFile();goal.schemaOutput=root.resolve("output").toFile();
        for(var setting:Map.of("adminUrl",adminUrl,"user",user,"password",password).entrySet()) {var field=SchemaCheckMojo.class.getDeclaredField(setting.getKey());field.setAccessible(true);field.set(goal,setting.getValue());}
        goal.execute();Files.writeString(migrations.resolve("V20261115000300000__drift.sql"),"ALTER TABLE demo.\"Child\" DROP CONSTRAINT \"@fk:reference:parent\";");
        assertTrue(assertThrows(Exception.class,goal::execute).getMessage().contains("mismatch"));
    }
}
