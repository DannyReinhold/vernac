// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;
import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.vernac.runtime.authorization.*;
import org.vernac.runtime.tenancy.*;
import org.vernac.saas.identity.domain.*;
import org.vernac.saas.identity.persistence.domain.*;
import org.vernac.saas.identity.security.*;
import org.vernac.saas.identity.security.credentials.*;
import org.vernac.saas.mail.domain.*;
@EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
class IdentityJourneysPostgresIT {
    @TempDir Path root;
    static final Instant NOW=Instant.parse("2026-11-15T12:00:00Z");
    static final String ADDRESS="member@example.test",PASSWORD="independent credential phrase number one 7839",SECOND="independent replacement phrase number two 2947",THIRD="a third independent passphrase number 5639";
    static final class ClockControl extends Clock {
        volatile Instant now=NOW;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}
    }
    static final class Fixture implements AutoCloseable {
        final org.springframework.boot.test.context.assertj.AssertableApplicationContext context;
        final NamedParameterJdbcTemplate jdbc;final PlatformTransactionManager manager;final ClockControl clock;
        final IdentityJdbcAuthentication authentication;final IdentityAccounts accounts;final IdentityJourneys flow;
        final LinkedBlockingQueue<OutboundMail> delivered=new LinkedBlockingQueue<>();
        Fixture(org.springframework.boot.test.context.assertj.AssertableApplicationContext context,BoundedCredentialPasswords passwords,ClockControl clock) {
            assertNull(context.getStartupFailure());this.context=context;this.clock=clock;jdbc=context.getBean(NamedParameterJdbcTemplate.class);manager=context.getBean(PlatformTransactionManager.class);
            authentication=IdentityJdbcAuthentication.initializeDefaultPolicy(jdbc,manager,clock,"deployment-v1",IdentityAdmissionPostgresIT.key(7),passwords);
            accounts=new IdentityAccounts(jdbc,manager,clock,authentication.sessionTrust(),AccountPolicyPostgresIT.catalog(AccountPolicyPostgresIT.CATALOG),List.of("example.reader"),r->PermissionDecision.ALLOW);
            var provider=new MailProvider(){public MailProviderMode mode(){return MailProviderMode.SIMULATED;}public MailSubmission submit(OutboundMail mail){
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertTrue(TenantContext.currentTenant().isEmpty());assertTrue(AuthorizationGuard.currentActor().isEmpty());
                delivered.add(mail);return MailSubmission.of(MailAcceptance.ACCEPTED,MailRetryDelay.of(0));}};
            byte[] encryption=new byte[32];Arrays.fill(encryption,(byte)3);
            flow=new IdentityJourneys(jdbc,manager,clock,authentication,accounts,passwords,provider,URI.create("https://localhost"),true,"active",Map.of("active",encryption));
        }
        InetAddress source(){return IdentityAdmissionPostgresIT.ip(55);}
        void start(){flow.mailLifecycle().start();}
        String token(String route) throws Exception {
            var mail=delivered.poll(8,TimeUnit.SECONDS);assertNotNull(mail,"The bounded local provider did not receive committed mail");
            var matcher=java.util.regex.Pattern.compile("https://localhost/auth/"+route+"#([^\\s]+)").matcher(mail.text().text());
            assertTrue(matcher.find(),"Missing expected purpose-bound local link");return URI.create(matcher.group()).getFragment();
        }
        long count(String table){return Objects.requireNonNull(jdbc.getJdbcTemplate().queryForObject("SELECT count(*) FROM \"org.vernac.saas.identity.persistence\".\""+table+"\"",Long.class));}
        UserId subject(){return new TransactionTemplate(manager).execute(status->context.getBean(IdentityLoginAddressRepository.class).forKey(IdentityLoginKey.of(ADDRESS)).orElseThrow().id());}
        org.springframework.security.core.Authentication login(String password){return authentication.authenticationManager().authenticate(IdentityAuthenticationPostgresIT.request(ADDRESS,password));}
        String register() throws Exception {flow.requestRegistration(ADDRESS,source());return token("verify");}
        @Override public void close() throws Exception {var stopped=new CountDownLatch(1);flow.mailLifecycle().stop(stopped::countDown);assertTrue(stopped.await(10,TimeUnit.SECONDS));SecurityContextHolder.clearContext();}
    }
    @Test void verificationCommitsCredentialsActivationAccountAndFirstOwnerAtomicallyAndDoesNotLogIn() throws Exception {
        try(var passwords=new BoundedCredentialPasswords(new CredentialPasswords(OfflinePasswordBlocklist.bundled()))) {
            new IdentityJdbcTestEnvironment(root).withDatabase(db->db.boot.run(context->{try(var f=new Fixture(context,passwords,new ClockControl())){
                f.start();String token=f.register();assertEquals(1,f.count("IdentityPendingRegistration"));assertEquals(0,f.count("Account"));
                assertTrue(f.flow.verify(token,PASSWORD,f.source()));assertNull(SecurityContextHolder.getContext().getAuthentication());
                for(String table:List.of("IdentityCredential","Account","AccountMembership","PersonalAccount"))assertEquals(1,f.count(table),table);
                assertEquals(0,f.count("IdentityPendingRegistration"));assertEquals(0,f.count("IdentityChallenge"));
                assertFalse(f.flow.verify(token,PASSWORD,f.source()));assertEquals(1,f.count("Account"));
                var user=f.subject();var tx=new TransactionTemplate(f.manager);
                assertEquals(IdentityStatus.ACTIVE,tx.execute(status->context.getBean(IdentityRepository.class).byId(user).status()));
                var owner=tx.execute(status->context.getBean(PersonalAccountRepository.class).existing(user).orElseThrow());assertEquals(user,owner.subject());
                var member=tx.execute(status->context.getBean(AccountMembershipRepository.class).forKey(IdentityAccounts.key(owner.account(),user)).orElseThrow());
                assertTrue(member.owner().enabled());assertEquals("example.reader",member.roles().encoded());assertEquals(new IdentityPrincipal(user),f.login(PASSWORD).getPrincipal());
            }}));
        }
    }
    @Test void failedProvisioningRollsBackTokenPasswordActivationAndWorkspaceAndCanRetryAfterRestart() throws Exception {
        var token=new java.util.concurrent.atomic.AtomicReference<String>();
        try(var passwords=new BoundedCredentialPasswords(new CredentialPasswords(OfflinePasswordBlocklist.bundled()))) {
            new IdentityJdbcTestEnvironment(root).withDatabase(db->{
                db.boot.run(context->{try(var f=new Fixture(context,passwords,new ClockControl())){
                    f.start();token.set(f.register());
                    try(var admin=java.sql.DriverManager.getConnection(db.url,db.credentials);var sql=admin.createStatement()){
                        sql.execute("REVOKE INSERT ON \"org.vernac.saas.identity.persistence\".\"PersonalAccount\" FROM "+db.role);
                        var failure=assertThrows(IdentityActionException.class,()->f.flow.verify(token.get(),PASSWORD,f.source()));
                        assertEquals(IdentityActionException.Code.UNAVAILABLE,failure.code());assertNull(failure.getCause());
                        sql.execute("GRANT INSERT ON \"org.vernac.saas.identity.persistence\".\"PersonalAccount\" TO "+db.role);
                    }
                    for(String table:List.of("IdentityCredential","Account","AccountMembership","PersonalAccount"))assertEquals(0,f.count(table),table);
                    assertEquals(1,f.count("IdentityPendingRegistration"));assertEquals(1,f.count("IdentityChallenge"));
                    assertEquals(IdentityStatus.PENDING_VERIFICATION,new TransactionTemplate(f.manager).execute(status->context.getBean(IdentityRepository.class).byId(f.subject()).status()));
                }});
                db.boot.run(context->{try(var f=new Fixture(context,passwords,new ClockControl())){
                    assertTrue(f.flow.verify(token.get(),PASSWORD,f.source()));assertEquals(1,f.count("PersonalAccount"));assertFalse(f.flow.verify(token.get(),PASSWORD,f.source()));
                }});
            });
        }
    }
    @Test void concurrentVerificationAcrossContextsHasOneWinnerAndOnePersonalAccount() throws Exception {
        try(var passwords=new BoundedCredentialPasswords(new CredentialPasswords(OfflinePasswordBlocklist.bundled()))) {
            new IdentityJdbcTestEnvironment(root).withDatabase(db->db.boot.run(first->db.boot.run(second->{
                try(var one=new Fixture(first,passwords,new ClockControl());var two=new Fixture(second,passwords,new ClockControl())){
                    one.start();String token=one.register();var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
                    try {
                        var a=pool.submit(()->{start.await();return one.flow.verify(token,PASSWORD,one.source());});
                        var b=pool.submit(()->{start.await();return two.flow.verify(token,PASSWORD,two.source());});start.countDown();
                        assertNotEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));assertEquals(1,one.count("PersonalAccount"));assertEquals(1,one.count("AccountMembership"));
                    }finally{pool.shutdownNow();}
                }
            })));
        }
    }
    @Test void recoveryAndAuthenticatedChangeAdvanceEpochRequireReauthenticationAndNeverCreateAccountsOrSessions() throws Exception {
        try(var passwords=new BoundedCredentialPasswords(new CredentialPasswords(OfflinePasswordBlocklist.bundled()))) {
            new IdentityJdbcTestEnvironment(root).withDatabase(db->db.boot.run(context->{try(var f=new Fixture(context,passwords,new ClockControl())){
                f.start();assertTrue(f.flow.verify(f.register(),PASSWORD,f.source()));var old=f.login(PASSWORD);
                f.flow.requestRecovery(ADDRESS,f.source());String reset=f.token("recover");
                assertFalse(f.flow.verify(reset,SECOND,f.source()));assertTrue(f.flow.recover(reset,SECOND,f.source()));assertFalse(f.flow.recover(reset,SECOND,f.source()));
                assertFalse(f.authentication.sessionTrust().current(old));assertNull(SecurityContextHolder.getContext().getAuthentication());
                assertThrows(BadCredentialsException.class,()->f.login(PASSWORD));var current=f.login(SECOND);SecurityContextHolder.getContext().setAuthentication(current);
                assertFalse(f.flow.changePassword("wrong",THIRD,f.source()));assertTrue(f.flow.changePassword(SECOND,THIRD,f.source()));
                assertFalse(f.authentication.sessionTrust().current(current));assertNotNull(f.login(THIRD));assertEquals(1,f.count("Account"));
                assertEquals(IdentityActionException.Code.AUTHENTICATION_REQUIRED,assertThrows(IdentityActionException.class,()->f.flow.changePassword(THIRD,PASSWORD,f.source())).code());
            }}));
        }
    }
    @Test void weakPasswordsExpiredLinksAndNeutralUnknownRecoveryDoNotConsumeAValidChallengeOrCreateIdentity() throws Exception {
        try(var passwords=new BoundedCredentialPasswords(new CredentialPasswords(OfflinePasswordBlocklist.bundled()))) {
            new IdentityJdbcTestEnvironment(root).withDatabase(db->db.boot.run(context->{try(var f=new Fixture(context,passwords,new ClockControl())){
                f.start();f.flow.requestRecovery("unknown@example.test",f.source());assertEquals(0,f.count("Identity"));assertEquals(0,f.count("IdentityMailJob"));
                String token=f.register();assertEquals(IdentityActionException.Code.PASSWORD_POLICY,assertThrows(IdentityActionException.class,()->f.flow.verify(token,"weak",f.source())).code());
                assertEquals(1,f.count("IdentityChallenge"));assertTrue(f.flow.verify(token,PASSWORD,f.source()));
                f.flow.requestRecovery(ADDRESS,f.source());String reset=f.token("recover");f.clock.now=NOW.plus(Duration.ofMinutes(30));
                assertFalse(f.flow.recover(reset,SECOND,f.source()));assertNotNull(f.login(PASSWORD));
            }}));
        }
    }
}
