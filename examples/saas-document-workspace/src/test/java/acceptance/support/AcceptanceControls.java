// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package acceptance.support;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.bind.annotation.*;
import org.vernac.saas.billing.lifecycle.BillingMaintenance;
import org.vernac.saas.payments.local.LocalPayments;

/** Private test-only controls. Observe durable facts; never manufacture paid subscriptions/invoices. */
@Configuration(proxyBeanMethods=false)
@Import(AcceptanceControls.Endpoints.class)
public class AcceptanceControls {
    @Bean @Order(-20) SecurityFilterChain fixtureChain(HttpSecurity http,Environment environment)throws Exception {
        byte[] expected=("Bearer "+environment.getRequiredProperty("acceptance.capability")).getBytes(StandardCharsets.US_ASCII);
        if(expected.length!=50)throw new IllegalStateException("Invalid acceptance capability");
        var gate=new OncePerRequestFilter(){
            protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws java.io.IOException,ServletException {
                String provided=request.getHeader("Authorization");response.setHeader("Cache-Control","no-store");
                if(!request.isSecure() || !request.getMethod().equals("POST") || request.getQueryString()!=null
                        || provided==null || Collections.list(request.getHeaders("Authorization")).size()!=1
                        || provided.length()!=expected.length || !MessageDigest.isEqual(expected,provided.getBytes(StandardCharsets.US_ASCII))){response.setStatus(403);return;}
                chain.doFilter(request,response);
            }
        };
        return http.securityMatcher(r->r.getServletPath().startsWith("/__acceptance/"))
                .sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable).csrf(AbstractHttpConfigurer::disable)
                .addFilterBefore(gate,SecurityContextHolderFilter.class).authorizeHttpRequests(c->c.anyRequest().permitAll()).build();
    }
    @RestController static class Endpoints {
        final AcceptanceConfiguration.Time time;final AcceptanceConfiguration.Faults faults;final BillingMaintenance maintenance;
        final NamedParameterJdbcTemplate jdbc;final Environment environment;
        Connection barrier;
        Endpoints(AcceptanceConfiguration.Time time,AcceptanceConfiguration.Faults faults,BillingMaintenance maintenance,NamedParameterJdbcTemplate jdbc,Environment environment){this.time=time;this.faults=faults;this.maintenance=maintenance;this.jdbc=jdbc;this.environment=environment;}
        @PostMapping("/__acceptance/time") Map<String,Object> time(@RequestBody Map<String,String> body)throws Exception {
            time.advance(Long.parseLong(body.getOrDefault("seconds","0")));return Map.of("instant",time.instant().toString(),"millis",time.instant().toEpochMilli());
        }
        @PostMapping("/__acceptance/faults") Map<String,String> faults(@RequestBody Map<String,String> body) {
            if(body.containsKey("scenario"))faults.scenario=Arrays.stream(body.get("scenario").split(",")).map(LocalPayments.Step::valueOf).toList();
            if(body.containsKey("render"))faults.render=Boolean.parseBoolean(body.get("render"));
            if(body.containsKey("mail"))faults.mail=Boolean.parseBoolean(body.get("mail"));
            if(body.containsKey("lostCheckout"))faults.lostCheckout.set(Boolean.parseBoolean(body.get("lostCheckout")));
            if(body.containsKey("lostRefund"))faults.lostRefund.set(Boolean.parseBoolean(body.get("lostRefund")));
            return Map.of("status","SET");
        }
        @PostMapping("/__acceptance/progress") Map<?,?> progress(){return maintenance.progress();}
        @PostMapping("/__acceptance/tick") Map<?,?> tick(){maintenance.tick();return maintenance.progress();}
        private Connection admin()throws Exception{return DriverManager.getConnection(environment.getRequiredProperty("spring.flyway.url"),environment.getRequiredProperty("spring.flyway.user"),environment.getRequiredProperty("spring.flyway.password"));}
        private String role(){var value=environment.getRequiredProperty("spring.datasource.username");if(!value.matches("[a-z0-9_]+"))throw new IllegalStateException();return "\""+value+"\"";}
        @PostMapping("/__acceptance/permission") Map<String,String> permission(@RequestBody Map<String,String> body)throws Exception {
            // Real database permission failure, not a fake ledger/worker. Restore before teardown.
            String table=switch(body.get("table")){case "renewal"->"\"org.vernac.saas.payments.persistence\".\"BillingAgreement\"";case "invoice"->"\"org.vernac.saas.invoicing\".\"Invoice\"";default->throw new IllegalArgumentException();};
            try(var c=admin();var sql=c.createStatement()){sql.execute(("true".equals(body.get("deny"))?"REVOKE SELECT ON ":"GRANT SELECT ON ")+table+("true".equals(body.get("deny"))?" FROM ":" TO ")+role());}
            return Map.of("status","SET");
        }
        @PostMapping("/__acceptance/barrier") synchronized Map<String,String> barrier(@RequestBody Map<String,String> body)throws Exception {
            if("release".equals(body.get("action"))){if(barrier!=null){barrier.rollback();barrier.close();barrier=null;}return Map.of("status","RELEASED");}
            if(barrier!=null)throw new IllegalStateException();barrier=admin();barrier.setAutoCommit(false);
            try(var sql=barrier.prepareStatement("SELECT \"id\" FROM \"org.vernac.saas.identity.persistence\".\"Account\" WHERE \"id\"=? FOR UPDATE")){sql.setObject(1,UUID.fromString(body.get("account")));sql.executeQuery().close();}
            return Map.of("status","HELD");
        }
        @PostMapping("/__acceptance/blocked") Map<String,Long> blocked()throws Exception {
            try(var c=admin();var sql=c.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE usename=? AND wait_event_type='Lock'")){sql.setString(1,environment.getRequiredProperty("spring.datasource.username"));try(var rows=sql.executeQuery()){rows.next();return Map.of("count",rows.getLong(1));}}
        }
        @PostMapping("/__acceptance/facts") Map<String,Object> facts() {
            Map<String,Object> out=new LinkedHashMap<>();
            out.put("accounts",jdbc.queryForList("SELECT \"id\"::text AS id,\"status\" AS status FROM \"org.vernac.saas.identity.persistence\".\"Account\" ORDER BY \"id\"" ,Map.of()));
            out.put("subscriptions",jdbc.queryForList("SELECT \"account\"::text AS account,\"status\" AS status,\"terms.plan\" AS plan,\"terms.price\" AS price,\"renewal\" AS renewal,\"renewalTerms.plan\" AS next,extract(epoch from \"period.start\")*1000 AS start,extract(epoch from \"period.end\")*1000 AS end FROM \"org.vernac.saas.subscriptions\".\"Subscription\" ORDER BY \"id\"",Map.of()));
            out.put("orders",jdbc.queryForList("SELECT \"id\"::text AS id,\"account\"::text AS account,\"status\" AS status,\"reason\" AS reason,\"terms.price\" AS price FROM \"org.vernac.saas.payments.persistence\".\"PaymentOrder\" ORDER BY \"@createdAt\",\"id\"",Map.of()));
            out.put("attempts",jdbc.queryForList("SELECT \"order\"::text AS order,\"stage\" AS stage,\"state\" AS state,\"remote\" AS remote,\"refunded\" AS refunded FROM \"org.vernac.saas.payments.persistence\".\"PaymentAttempt\" ORDER BY \"@createdAt\",\"id\"",Map.of()));
            out.put("refunds",jdbc.queryForList("SELECT \"id\"::text AS id,\"state\" AS state,\"reason\" AS reason,\"amount\" AS amount FROM \"org.vernac.saas.payments.persistence\".\"PaymentRefund\" ORDER BY \"@createdAt\",\"id\"",Map.of()));
            out.put("invoices",jdbc.queryForList("SELECT \"id\"::text AS id,\"account\"::text AS account,\"status\" AS status,\"content.kind\" AS kind,\"content.number\" AS number,\"content.original.number\" AS original,\"content.amounts.net\" AS net,\"content.amounts.tax\" AS tax,\"content.amounts.gross\" AS gross FROM \"org.vernac.saas.invoicing\".\"Invoice\" ORDER BY \"content.number\"",Map.of()));
            out.put("notices",jdbc.queryForList("SELECT \"status\" AS status FROM \"org.vernac.saas.invoicing\".\"InvoiceNotice\" ORDER BY \"id\"",Map.of()));
            out.put("providerPayments",jdbc.queryForObject("SELECT count(*) FROM \"org.vernac.saas.payments.local\".\"LocalPayment\"",Map.of(),Long.class));
            out.put("providerRefunds",jdbc.queryForObject("SELECT count(*) FROM \"org.vernac.saas.payments.local\".\"LocalRefund\"",Map.of(),Long.class));
            return out;
        }
    }
}
