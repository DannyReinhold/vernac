// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import javax.sql.DataSource;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.web.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.session.jdbc.config.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.vernac.runtime.authorization.*;
import org.vernac.runtime.tenancy.*;
import org.vernac.saas.identity.domain.*;
import org.vernac.saas.identity.security.*;
import org.vernac.saas.identity.security.credentials.*;

/** Full configured browser boundary with packaged identity module, actual metadata/mail and shared sessions. */
@EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
class IdentityBrowserPostgresIT {
    @TempDir Path root;
    static final String COOKIE="__Host-VERNACSESSION";
    @Configuration(proxyBeanMethods=false) @EnableWebSecurity @Import(IdentityJdbcSessions.class)
    static class Web {
        @Bean(destroyMethod="") @SpringSessionDataSource DataSource sessions(IdentityJourneysPostgresIT.Fixture f) {
            return f.jdbc.getJdbcTemplate().getDataSource();
        }
        @Bean @SpringSessionTransactionManager PlatformTransactionManager sessionsWriter(IdentityJourneysPostgresIT.Fixture f) {return f.manager;}
        @Bean SecurityFilterChain security(HttpSecurity http,IdentityJourneysPostgresIT.Fixture f)throws Exception {
            return IdentityBrowserSecurity.build(http,f.authentication.authenticationManager(),f.authentication.sessionTrust(),
                new IdentityLocalReturnTarget("/workspace"),f.authentication.requestAdmission(),f.flow,f.accounts);
        }
    }
    static class Browser {
        final AnnotationConfigWebApplicationContext web;Cookie cookie;
        Browser(AnnotationConfigWebApplicationContext web){this.web=web;}
        MockHttpServletResponse request(String method,String path,Map<String,String> values)throws Exception {
            var request=new MockHttpServletRequest(method,path);request.setServletPath(path);request.setScheme("https");
            request.setSecure(true);request.setServerPort(443);request.setRemoteAddr("192.0.2.55");
            if(cookie!=null)request.setCookies(cookie);
            if(method.equals("POST")) {
                request.setContentType("application/x-www-form-urlencoded");
                request.setContent(form(values).getBytes(StandardCharsets.UTF_8));
            }else values.forEach(request::setParameter);
            var response=new MockHttpServletResponse();
            web.getBean("springSessionRepositoryFilter",Filter.class).doFilter(request,response,(raw,out)->
                web.getBean("springSecurityFilterChain",Filter.class).doFilter(raw,out,(product,result)->{
                    var f=web.getBean(IdentityJourneysPostgresIT.Fixture.class);
                    var actor=new SessionIdentityActorResolver(f.authentication.sessionTrust()).resolve().orElseThrow();
                    var tenant=TenantContext.requireTenant();
                    var permission=new PermissionRequest("example.workspace.read",actor,tenant,AccountPolicyPostgresIT.Rights.READ,null);
                    assertEquals(PermissionDecision.ALLOW,f.accounts.evaluate(permission));
                    result.getWriter().write("scoped product");
                }));
            Cookie updated=response.getCookie(COOKIE);
            if(updated!=null)cookie=updated.getMaxAge()==0?null:updated;
            assertTrue(TenantContext.currentTenant().isEmpty());
            assertTrue(AuthorizationGuard.currentActor().isEmpty());
            return response;
        }
        MockHttpServletResponse get(String path)throws Exception{return request("GET",path,Map.of());}
        MockHttpServletResponse post(String path,Map<String,String> fields)throws Exception {
            var page=get(path.equals("/identity/login")?path:path.equals("/auth/verify")||path.equals("/auth/recover")||path.equals("/auth/register")||path.equals("/auth/recovery-request")||path.equals("/auth/invitation")?path:"/auth/accounts");
            var values=new HashMap<>(fields);values.put("_csrf",csrf(page));
            return request("POST",path,values);
        }
        void login(String address,String password)throws Exception {
            var page=get("/identity/login");var anonymous=cookie;assertNotNull(anonymous);
            assertTrue(anonymous.getSecure());assertTrue(anonymous.isHttpOnly());assertEquals("/",anonymous.getPath());
            assertNull(anonymous.getDomain());assertEquals("Lax",anonymous.getAttribute("SameSite"));
            var values=Map.of("address",address,"password",password,"_csrf",csrf(page));
            assertEquals(303,request("POST","/identity/login",values).getStatus());assertNotEquals(anonymous.getValue(),cookie.getValue());
        }
    }
    static String csrf(MockHttpServletResponse response)throws Exception {
        var m=java.util.regex.Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(response.getContentAsString());
        assertTrue(m.find(),"Expected a CSRF-protected form");return m.group(1);
    }
    static String form(Map<String,String> values) {
        return values.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
    }
    AnnotationConfigWebApplicationContext web(IdentityJourneysPostgresIT.Fixture f) {
        var web=new AnnotationConfigWebApplicationContext();web.setServletContext(new MockServletContext());
        web.addBeanFactoryPostProcessor(factory->factory.registerSingleton("journeyFixture",f));
        web.register(Web.class);web.refresh();return web;
    }
    void schema(IdentityJdbcTestEnvironment.Database db)throws Exception {
        try(var c=DriverManager.getConnection(db.url,db.credentials);var sql=c.createStatement()) {
            sql.execute("CREATE SCHEMA identity_sessions");c.setSchema("identity_sessions");
            ScriptUtils.executeSqlScript(c,new ClassPathResource("org/springframework/session/jdbc/schema-postgresql.sql"));
            sql.execute("GRANT USAGE ON SCHEMA identity_sessions TO "+db.role);
            sql.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA identity_sessions TO "+db.role);
        }
    }
    static AccountId account(Browser browser)throws Exception {
        String html=browser.get("/auth/accounts").getContentAsString();
        var matcher=java.util.regex.Pattern.compile("name=\"account\" value=\"([a-f0-9-]{36})\"").matcher(html);
        assertTrue(matcher.find());return AccountId.of(UUID.fromString(matcher.group(1)));
    }
    void register(Browser browser,IdentityJourneysPostgresIT.Fixture f,String address)throws Exception {
        assertEquals(202,browser.post("/auth/register",Map.of("address",address)).getStatus());
        String token=f.token("verify");var page=browser.get("/auth/verify");
        assertEquals("no-store",page.getHeader("Cache-Control"));assertEquals("no-referrer",page.getHeader("Referrer-Policy"));
        assertFalse(page.getContentAsString().contains(token));assertTrue(page.getContentAsString().contains("history.replaceState"));
        assertEquals(303,browser.post("/auth/verify",Map.of("token",token,"password",IdentityJourneysPostgresIT.PASSWORD)).getStatus());
        assertEquals(401,browser.get("/auth/accounts").getStatus());
    }
    @Test void registrationLoginSelectionAndRecoveryUseRealMetadataMailAndSharedSessionsAcrossRestart()throws Exception {
        try(var passwords=new BoundedCredentialPasswords(new CredentialPasswords(OfflinePasswordBlocklist.bundled()))) {
            new IdentityJdbcTestEnvironment(root).withDatabase(db->{schema(db);
                db.boot.run(first->db.boot.run(second->{
                    try(var one=new IdentityJourneysPostgresIT.Fixture(first,passwords,new IdentityJourneysPostgresIT.ClockControl());
                        var two=new IdentityJourneysPostgresIT.Fixture(second,passwords,new IdentityJourneysPostgresIT.ClockControl());
                        var a=web(one);var b=web(two)) {
                        one.start();var browser=new Browser(a);
                        assertEquals(403,browser.request("POST","/auth/register",Map.of("address",IdentityJourneysPostgresIT.ADDRESS)).getStatus());
                        assertEquals(0,one.count("Identity"));register(browser,one,IdentityJourneysPostgresIT.ADDRESS);
                        browser.login(IdentityJourneysPostgresIT.ADDRESS,IdentityJourneysPostgresIT.PASSWORD);
                        AccountId account=account(browser);
                        assertEquals(303,browser.post("/auth/account/select",Map.of("account",account.asString())).getStatus());
                        assertEquals("scoped product",browser.get("/workspace").getContentAsString());
                        var peer=new Browser(b);peer.cookie=browser.cookie;
                        assertEquals("scoped product",peer.get("/workspace").getContentAsString());
                        try(var restarted=web(two)) {
                            var reloaded=new Browser(restarted);reloaded.cookie=browser.cookie;
                            assertEquals("scoped product",reloaded.get("/workspace").getContentAsString());
                        }
                        var anonymous=new Browser(b);
                        assertEquals(202,anonymous.post("/auth/recovery-request",Map.of("address",IdentityJourneysPostgresIT.ADDRESS)).getStatus());
                        var reset=one.token("recover");
                        assertEquals(303,anonymous.post("/auth/recover",Map.of("token",reset,"password",IdentityJourneysPostgresIT.SECOND)).getStatus());
                        assertEquals(401,browser.get("/auth/accounts").getStatus());assertEquals(401,peer.get("/workspace").getStatus());
                        anonymous.login(IdentityJourneysPostgresIT.ADDRESS,IdentityJourneysPostgresIT.SECOND);
                        assertEquals(1,one.count("PersonalAccount"));assertEquals(1,one.count("Account"));
                    }
                }));
            });
        }
    }
    @Test void invitationAcceptanceRoleChangesLastOwnerAndMembershipRevocationCrossTheActualBrowserBoundary()throws Exception {
        try(var passwords=new BoundedCredentialPasswords(new CredentialPasswords(OfflinePasswordBlocklist.bundled()))) {
            new IdentityJdbcTestEnvironment(root).withDatabase(db->{schema(db);db.boot.run(context->{
                try(var f=new IdentityJourneysPostgresIT.Fixture(context,passwords,new IdentityJourneysPostgresIT.ClockControl());var app=web(f)) {
                    f.start();var owner=new Browser(app);var member=new Browser(app);String recipient="invitee@example.test";
                    register(owner,f,IdentityJourneysPostgresIT.ADDRESS);register(member,f,recipient);
                    owner.login(IdentityJourneysPostgresIT.ADDRESS,IdentityJourneysPostgresIT.PASSWORD);
                    member.login(recipient,IdentityJourneysPostgresIT.PASSWORD);
                    var account=account(owner);
                    assertEquals(202,owner.post("/auth/account/invite",Map.of("account",account.asString(),"address",recipient,"roles","example.reader")).getStatus());
                    String invitation=f.token("invitation");
                    assertEquals(303,member.post("/auth/invitation",Map.of("token",invitation)).getStatus());
                    assertEquals(400,member.post("/auth/invitation",Map.of("token",invitation)).getStatus());
                    assertEquals(303,member.post("/auth/account/select",Map.of("account",account.asString())).getStatus());
                    assertEquals("scoped product",member.get("/workspace").getContentAsString());
                    assertEquals(409,owner.post("/auth/identity/disable",Map.of()).getStatus());
                    UserId subject=f.jdbc.queryForObject("SELECT \"id\" FROM \"org.vernac.saas.identity.persistence\".\"IdentityLoginAddress\" WHERE \"loginKey\"=:address",Map.of("address",recipient),(rs,n)->UserId.of(rs.getObject(1,UUID.class)));
                    assertEquals(403,member.post("/auth/account/roles",Map.of("account",account.asString(),"subject",subject.asString(),"roles","example.editor")).getStatus());
                    assertEquals(303,owner.post("/auth/account/remove",Map.of("account",account.asString(),"subject",subject.asString())).getStatus());
                    assertEquals(403,member.get("/workspace").getStatus());assertEquals(200,member.get("/auth/accounts").getStatus());
                    assertEquals(1,f.jdbc.queryForObject("SELECT count(*) FROM \"org.vernac.saas.identity.persistence\".\"AccountMembership\" WHERE \"account\"=:id AND \"status\"='ACTIVE'",Map.of("id",account.value()),Integer.class));
                }
            });});
        }
    }
}
