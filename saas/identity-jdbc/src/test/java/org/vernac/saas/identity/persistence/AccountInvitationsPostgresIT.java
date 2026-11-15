// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.vernac.saas.identity.domain.*;
import org.vernac.saas.identity.persistence.domain.*;
import org.vernac.saas.identity.security.*;
import org.vernac.saas.identity.security.credentials.*;
@EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
class AccountInvitationsPostgresIT {
    @TempDir Path root;
    static final String OWNER="member@example.test",TARGET="invited@example.test",OTHER="other@example.test";
    static final String PASSWORD=IdentityJourneysPostgresIT.PASSWORD;
    static UserId register(IdentityJourneysPostgresIT.Fixture f,String address)throws Exception {
        f.flow.requestRegistration(address,f.source());assertTrue(f.flow.verify(f.token("verify"),PASSWORD,f.source()));
        return new TransactionTemplate(f.manager).execute(status->f.context.getBean(IdentityLoginAddressRepository.class).forKey(IdentityLoginKey.of(address)).orElseThrow().id());
    }
    static Authentication authenticate(IdentityJourneysPostgresIT.Fixture f,String address) {
        var authentication=f.authentication.authenticationManager().authenticate(IdentityAuthenticationPostgresIT.request(address,PASSWORD));SecurityContextHolder.getContext().setAuthentication(authentication);return authentication;
    }
    static AccountMembership member(IdentityJourneysPostgresIT.Fixture f,AccountId account,UserId user) {
        return new TransactionTemplate(f.manager).execute(status->f.context.getBean(AccountMembershipRepository.class).forKey(IdentityAccounts.key(account,user)).orElseThrow());
    }
    @Test void intendedVerifiedRecipientCanAcceptOnceWithoutOwnerPromotionAndWrongPurposeOrIdentityCannot()throws Exception {
        try(var passwords=new BoundedCredentialPasswords(new CredentialPasswords(OfflinePasswordBlocklist.bundled()))) {
            new IdentityJdbcTestEnvironment(root).withDatabase(db->db.boot.run(context->{try(var f=new IdentityJourneysPostgresIT.Fixture(context,passwords,new IdentityJourneysPostgresIT.ClockControl())){
                f.start();register(f,OWNER);register(f,OTHER);authenticate(f,OWNER);var account=f.accounts.createOrganization();
                f.flow.requestInvitation(account,TARGET,List.of("example.editor"),f.source());String token=f.token("invitation");
                assertEquals(1,f.count("AccountInvitation"));assertFalse(f.flow.recover(token,IdentityJourneysPostgresIT.SECOND,f.source()));
                authenticate(f,OTHER);assertFalse(f.flow.acceptInvitation(token,f.source()));assertEquals(1,f.count("AccountInvitation"));
                SecurityContextHolder.clearContext();var target=register(f,TARGET);authenticate(f,TARGET);
                assertTrue(f.flow.acceptInvitation(token,f.source()));assertFalse(f.flow.acceptInvitation(token,f.source()));
                var membership=member(f,account,target);assertFalse(membership.owner().enabled());assertEquals("example.editor",membership.roles().encoded());
                assertEquals(0,f.count("AccountInvitation"));assertNotNull(SecurityContextHolder.getContext().getAuthentication());assertTrue(f.authentication.sessionTrust().current(SecurityContextHolder.getContext().getAuthentication()));
            }}));
        }
    }
    @Test void existingMembershipIsIdempotentAndInvitationsCannotSilentlyEscalateRoles()throws Exception {
        try(var passwords=new BoundedCredentialPasswords(new CredentialPasswords(OfflinePasswordBlocklist.bundled()))) {
            new IdentityJdbcTestEnvironment(root).withDatabase(db->db.boot.run(context->{try(var f=new IdentityJourneysPostgresIT.Fixture(context,passwords,new IdentityJourneysPostgresIT.ClockControl())){
                f.start();register(f,OWNER);var target=register(f,TARGET);authenticate(f,OWNER);var account=f.accounts.createOrganization();
                f.flow.requestInvitation(account,TARGET,List.of("example.reader"),f.source());var first=f.token("invitation");authenticate(f,TARGET);assertTrue(f.flow.acceptInvitation(first,f.source()));
                authenticate(f,OWNER);f.flow.requestInvitation(account,TARGET,List.of("example.editor"),f.source());var second=f.token("invitation");authenticate(f,TARGET);assertTrue(f.flow.acceptInvitation(second,f.source()));
                var membership=member(f,account,target);assertEquals("example.reader",membership.roles().encoded());assertFalse(membership.owner().enabled());
                assertEquals(IdentityActionException.Code.ACCESS_DENIED,assertThrows(IdentityActionException.class,()->f.flow.requestInvitation(account,OTHER,List.of(),f.source())).code());
            }}));
        }
    }
    @Test void concurrentAcceptanceAcrossTwoContextsHasOneWinnerAndOneMembership()throws Exception {
        try(var passwords=new BoundedCredentialPasswords(new CredentialPasswords(OfflinePasswordBlocklist.bundled()))) {
            new IdentityJdbcTestEnvironment(root).withDatabase(db->db.boot.run(first->db.boot.run(second->{
                try(var one=new IdentityJourneysPostgresIT.Fixture(first,passwords,new IdentityJourneysPostgresIT.ClockControl());var two=new IdentityJourneysPostgresIT.Fixture(second,passwords,new IdentityJourneysPostgresIT.ClockControl())){
                    one.start();register(one,OWNER);var target=register(one,TARGET);authenticate(one,OWNER);var account=one.accounts.createOrganization();one.flow.requestInvitation(account,TARGET,List.of("example.reader"),one.source());var token=one.token("invitation");
                    var aAuth=authenticate(one,TARGET);var bAuth=authenticate(two,TARGET);SecurityContextHolder.clearContext();var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
                    try{
                        var a=pool.submit(()->accept(one,aAuth,token,start));var b=pool.submit(()->accept(two,bAuth,token,start));start.countDown();
                        assertNotEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));assertFalse(member(one,account,target).owner().enabled());assertEquals(0,one.count("AccountInvitation"));
                        assertEquals(1,one.jdbc.queryForObject("SELECT count(*) FROM \"org.vernac.saas.identity.persistence\".\"AccountMembership\" WHERE \"account\"=:account AND \"subject\"=:subject",Map.of("account",account.value(),"subject",target.value()),Integer.class));
                    }finally{pool.shutdownNow();}
                }
            })));
        }
    }
    static boolean accept(IdentityJourneysPostgresIT.Fixture f,Authentication authentication,String token,CountDownLatch start)throws Exception {
        SecurityContextHolder.getContext().setAuthentication(authentication);try{start.await();return f.flow.acceptInvitation(token,f.source());}finally{SecurityContextHolder.clearContext();}
    }
    @Test void revocationExpiryAndLostIssuerOwnershipInvalidateLinksAndBoundedCleanupRefundsCapacity()throws Exception {
        try(var passwords=new BoundedCredentialPasswords(new CredentialPasswords(OfflinePasswordBlocklist.bundled()))) {
            new IdentityJdbcTestEnvironment(root).withDatabase(db->db.boot.run(context->{try(var f=new IdentityJourneysPostgresIT.Fixture(context,passwords,new IdentityJourneysPostgresIT.ClockControl())){
                f.start();register(f,OWNER);register(f,TARGET);authenticate(f,OWNER);var account=f.accounts.createOrganization();
                f.flow.requestInvitation(account,TARGET,List.of(),f.source());String token=f.token("invitation");
                f.accounts.revokeInvitation(account,InvitationId.of(IdentityChallengeToken.parse(token).locator()));authenticate(f,TARGET);assertFalse(f.flow.acceptInvitation(token,f.source()));
                authenticate(f,OWNER);f.flow.requestInvitation(account,TARGET,List.of(),f.source());String expired=f.token("invitation");f.clock.now=IdentityJourneysPostgresIT.NOW.plus(Duration.ofDays(7));authenticate(f,TARGET);assertFalse(f.flow.acceptInvitation(expired,f.source()));
                // Refresh the complete gate's windows before testing issuer loss on a new pending message.
                authenticate(f,OWNER);var stopped=new CountDownLatch(1);f.flow.mailLifecycle().stop(stopped::countDown);assertTrue(stopped.await(10,TimeUnit.SECONDS));
                f.flow.requestInvitation(account,"next@example.test",List.of(),f.source());
                var metadata=new AccountMetadataPostgresIT.Fixture(context);var successor=metadata.identity();var stored=new TransactionTemplate(f.manager).execute(status->context.getBean(AccountRepository.class).byId(account));metadata.member(stored,successor,false,"");
                f.accounts.transferOwnership(account,successor);SecurityContextHolder.clearContext();f.flow.mailLifecycle().start();
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);while((f.count("AccountInvitation")!=0 || f.count("IdentityMailJob")!=0) && System.nanoTime()<end)Thread.sleep(20);
                assertEquals(0,f.count("AccountInvitation"));assertEquals(0,f.count("IdentityMailJob"));assertNull(f.delivered.poll(150,TimeUnit.MILLISECONDS));
                Long used=new TransactionTemplate(f.manager).execute(status->new JdbcIdentityCapacity(f.jdbc).usage(JdbcIdentityCapacity.Resource.ACTIONABLE_CHALLENGES));assertEquals(Long.valueOf(0),used);
            }}));
        }
    }
}
