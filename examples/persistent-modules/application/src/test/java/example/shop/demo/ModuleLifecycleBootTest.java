// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package example.shop.demo;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Exercise real Boot auto-configuration and the Runner against a disposable database. */
class ModuleLifecycleBootTest {
    @Test @EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL", matches=".+")
    void startsThroughBootAutoConfigurationAndRunsTheCrossModuleWalkthrough() throws Exception {
        String url = System.getenv("VERNAC_PG_TEST_URL");
        var credentials = new Properties();
        String user = System.getenv("VERNAC_PG_TEST_USER"), password = System.getenv("VERNAC_PG_TEST_PASSWORD");
        if (user != null) credentials.setProperty("user", user);
        if (password != null) credentials.setProperty("password", password);
        String database = "vernac_module_demo_" + UUID.randomUUID().toString().replace("-", "");
        int query = url.indexOf('?');String suffix = query < 0 ? "" : url.substring(query);
        String base = query < 0 ? url : url.substring(0,query);
        String isolated = base.substring(0,base.lastIndexOf('/')+1) + database + suffix;
        try (var admin = DriverManager.getConnection(url,credentials);var sql = admin.createStatement()) {
            sql.execute("CREATE DATABASE " + database);
            try {
                var arguments = new ArrayList<String>();arguments.add("--spring.datasource.url=" + isolated);
                arguments.add("--spring.main.web-application-type=servlet");arguments.add("--server.port=0");
                if(user != null) arguments.add("--spring.datasource.username=" + user);
                if(password != null) arguments.add("--spring.datasource.password=" + password);
                try(var context = new SpringApplicationBuilder(ModuleLifecycleApplication.class)
        .web(WebApplicationType.SERVLET).run(arguments.toArray(String[]::new))) {
     var jdbc = context.getBean(JdbcTemplate.class);
     assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM \"example.billing\".\"Invoice\"",Long.class));
     assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM \"example.receipts\".\"Receipt\"",Long.class));
     assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM \"example.shop\".\"Account\"",Long.class));
     assertEquals(1,context.getBeansOfType(org.vernac.runtime.jdbc.JdbcEventWorker.class).size());
     assertEquals(1,context.getBeansOfType(org.vernac.runtime.events.EventStorage.class).size());
     assertEquals(2L,jdbc.queryForObject("SELECT count(*) FROM \"vernac\".\"@delivery\" WHERE status='SUCCEEDED'",Long.class));
     assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM \"example.receipts\".\"Notification\"",Long.class));
     assertEquals(3L,jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name='flyway_schema_history'",Long.class));
     int port=((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext)context).getWebServer().getPort();
     var client=java.net.http.HttpClient.newHttpClient();
     var endpoint=java.net.URI.create("http://localhost:"+port+"/api/module-purchases");
     for(String body:java.util.List.of("{\"text\":\"HTTP module customer\"}","{}","{\"text\":\"\"}")) {
         var request=java.net.http.HttpRequest.newBuilder(endpoint).header("Content-Type","application/json")
             .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
         var response=client.send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
         int expected=body.equals("{}")?400:body.contains("HTTP module customer")?201:422;
         assertEquals(expected,response.statusCode(),response.body());
         if(expected==201)UUID.fromString(new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body()).get("invoiceId").asText());
         if(expected==400)org.junit.jupiter.api.Assertions.assertTrue(response.body().contains("INVALID_PURCHASE_REQUEST"));
         if(expected==422)org.junit.jupiter.api.Assertions.assertTrue(response.body().contains("INVALID_CUSTOMER"));
     }
     assertEquals(2L,jdbc.queryForObject("SELECT count(*) FROM \"example.billing\".\"Invoice\"",Long.class));
     assertEquals(2L,jdbc.queryForObject("SELECT count(*) FROM \"example.receipts\".\"Receipt\"",Long.class));

                }
            } finally {sql.execute("DROP DATABASE " + database + " WITH (FORCE)");}
        }
    }
}
