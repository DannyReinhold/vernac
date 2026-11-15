// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.vernac.runtime.tenancy.TenantId;
import org.vernac.saas.identity.domain.*;
import org.vernac.saas.identity.persistence.domain.*;
@EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
class AccountMetadataPostgresIT {
    @TempDir Path root;
    static final class Fixture {
        final org.springframework.boot.test.context.assertj.AssertableApplicationContext context;
        final NamedParameterJdbcTemplate jdbc;final TransactionTemplate writer;
        Fixture(org.springframework.boot.test.context.assertj.AssertableApplicationContext context) {
            assertNull(context.getStartupFailure());this.context=context;jdbc=context.getBean(NamedParameterJdbcTemplate.class);
            writer=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        }
        UserId identity() {return writer.execute(status->context.getBean(IdentityRepository.class).save(Identity.create(IdentityStatus.ACTIVE,IdentitySecurityEpoch.of(0))).id());}
        Account account(AccountKind kind) {return writer.execute(status->context.getBean(AccountRepository.class).save(Account.create(AccountTenantKey.of(UUID.randomUUID()),kind,AccountStatus.ACTIVE)));}
        AccountMembership member(Account account,UserId subject,boolean owner,String roles) {
            return writer.execute(status->context.getBean(AccountMembershipRepository.class).save(AccountMembership.create(account.id(),subject,
                AccountMemberKey.of(account.id().asString()+"/"+subject.asString()),AccountOwner.of(owner),AccountMemberStatus.ACTIVE,AccountRoleKeys.of(roles))));
        }
    }
    @Test void nominalMetadataPersistsAndDatabaseEnforcesTenantMembershipAndPersonalUniqueness() throws Exception {
        var accountId=new java.util.concurrent.atomic.AtomicReference<AccountId>();var subject=new java.util.concurrent.atomic.AtomicReference<UserId>();
        new IdentityJdbcTestEnvironment(root).withDatabase(db->{
            db.boot.run(context->{
                var f=new Fixture(context);subject.set(f.identity());var account=f.account(AccountKind.PERSONAL);accountId.set(account.id());
                var member=f.member(account,subject.get(),true,"example.reader");
                assertNotEquals(account.id(),UserId.of(account.id().value()));assertNotEquals(account.id(),TenantId.of(account.tenant().value()));
                assertThrows(RuntimeException.class,()->f.member(account,subject.get(),false,""));
                f.writer.executeWithoutResult(status->{var now=Instant.now();context.getBean(PersonalAccountRepository.class).save(PersonalAccount.reconstitute(subject.get(),subject.get(),account.id(),now,now,0));});
                var other=f.identity();
                assertThrows(RuntimeException.class,()->f.writer.executeWithoutResult(status->{var now=Instant.now();context.getBean(PersonalAccountRepository.class).save(PersonalAccount.reconstitute(other,other,account.id(),now,now,0));}));
                assertThrows(RuntimeException.class,()->f.writer.executeWithoutResult(status->context.getBean(AccountRepository.class).save(Account.create(account.tenant(),AccountKind.ORGANIZATION,AccountStatus.ACTIVE))));
                assertEquals(account.id(),f.writer.execute(status->context.getBean(AccountMembershipRepository.class).existing(member.id()).orElseThrow().account()));
                assertEquals(IdentityJdbcTestEnvironment.EXPECTED_MIGRATIONS,db.migrationCount());
            });
            db.boot.run(context->{var f=new Fixture(context);assertEquals(accountId.get(),f.writer.execute(status->context.getBean(PersonalAccountRepository.class).existing(subject.get()).orElseThrow().account()));});
        });
    }
    @Test void generatedForeignKeysRejectOrphanMetadataAndRuntimeCannotDropConstraints() throws Exception {
        new IdentityJdbcTestEnvironment(root).withDatabase(db->db.boot.run(context->{
            var f=new Fixture(context);var user=f.identity();var account=f.account(AccountKind.ORGANIZATION);var member=f.member(account,user,false,"");
            assertThrows(RuntimeException.class,()->f.jdbc.update("UPDATE \"org.vernac.saas.identity.persistence\".\"AccountMembership\" SET \"subject\"=:id WHERE \"id\"=:member",Map.of("id",UUID.randomUUID(),"member",member.id().value())));
            assertThrows(RuntimeException.class,()->f.jdbc.update("DELETE FROM \"org.vernac.saas.identity.persistence\".\"Account\" WHERE \"id\"=:id",Map.of("id",account.id().value())));
            assertThrows(RuntimeException.class,()->f.jdbc.getJdbcTemplate().execute("ALTER TABLE \"org.vernac.saas.identity.persistence\".\"AccountMembership\" DROP CONSTRAINT \"@fk:reference:subject\""));
            assertThrows(RuntimeException.class,()->AccountMembership.create(account.id(),user,AccountMemberKey.of(account.id().asString()+"/"+UserId.create().asString()),AccountOwner.of(false),AccountMemberStatus.ACTIVE,AccountRoleKeys.of("")));
        }));
    }
}
