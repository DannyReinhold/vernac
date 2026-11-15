// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;
import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.vernac.runtime.authorization.*;
import org.vernac.runtime.tenancy.*;
import org.vernac.saas.identity.domain.*;
import org.vernac.saas.identity.authorization.AccountPermissions;
import org.vernac.saas.identity.roles.*;
import org.vernac.saas.identity.security.*;
import org.vernac.saas.identity.persistence.domain.*;
@EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
class AccountPolicyPostgresIT {
    @TempDir Path root;
    static final Instant NOW=Instant.parse("2026-11-15T12:00:00Z");
    enum Rights implements PermissionKey { READ,EDIT;public String qualifiedName(){return "example.Documents."+name();} }
    static final String CATALOG="{\"format\":1,\"roles\":[{\"key\":\"example.reader\",\"permissions\":[\"example.Documents.READ\"]},{\"key\":\"example.editor\",\"permissions\":[\"example.Documents.EDIT\"]}]}";
    static RoleCatalog catalog(String text) {return RoleCatalog.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),List.of(Rights.values()));}
    static IdentityAccounts service(AccountMetadataPostgresIT.Fixture f,RoleCatalog catalog,PermissionEvaluator objects) {
        var manager=f.context.getBean(PlatformTransactionManager.class);
        return new IdentityAccounts(f.jdbc,manager,Clock.fixed(NOW,ZoneOffset.UTC),new IdentitySessionTrust(new JdbcIdentitySessionEpochs(f.jdbc,manager),Clock.fixed(NOW,ZoneOffset.UTC)),catalog,List.of(),objects);
    }
    static void authenticate(UserId user) {
        var token=UsernamePasswordAuthenticationToken.authenticated(new IdentityPrincipal(user),null,List.of());token.setDetails(new IdentitySessionStamp(0,NOW));
        SecurityContextHolder.getContext().setAuthentication(token);
    }
    static PermissionRequest request(UserId user,TenantId tenant,PermissionKey permission,ResourceReference<?> resource) {
        return new PermissionRequest("example.operation",new VerifiedActor(IdentityActorResolver.REALM,user.asString(),VerifiedActor.Kind.HUMAN),tenant,permission,resource);
    }
    @Test void twoContextsCannotBothRemoveTheLastSurvivingActiveOwner() throws Exception {
        new IdentityJdbcTestEnvironment(root).withDatabase(db->db.boot.run(first->db.boot.run(second->{
            var f=new AccountMetadataPostgresIT.Fixture(first);var a=f.identity();var b=f.identity();var account=f.account(AccountKind.ORGANIZATION);
            f.member(account,a,true,"");f.member(account,b,true,"");
            var one=service(f,catalog(CATALOG),r->PermissionDecision.ALLOW);var two=service(new AccountMetadataPostgresIT.Fixture(second),catalog(CATALOG),r->PermissionDecision.ALLOW);
            var start=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
            try {
                var x=executor.submit(()->remove(one,account.id(),a,start));var y=executor.submit(()->remove(two,account.id(),b,start));start.countDown();
                var outcomes=List.of(x.get(15,TimeUnit.SECONDS),y.get(15,TimeUnit.SECONDS));
                assertEquals(1,outcomes.stream().filter("REMOVED"::equals).count());assertEquals(1,outcomes.stream().filter("LAST_OWNER"::equals).count());
                assertEquals(1,f.jdbc.queryForObject("SELECT count(*) FROM \"org.vernac.saas.identity.persistence\".\"AccountMembership\" WHERE \"account\"=:account AND \"owner\" AND \"status\"='ACTIVE'",Map.of("account",account.id().value()),Integer.class));
            } finally {executor.shutdownNow();}
        })));
    }
    private static String remove(IdentityAccounts service,AccountId account,UserId user,CountDownLatch start) throws Exception {
        authenticate(user);try{start.await();service.removeMember(account,user);return "REMOVED";}catch(AccountAccessException failure){return failure.code().name();}finally{SecurityContextHolder.clearContext();}
    }
    @Test void freshRoleUnionSelectionAndObjectPolicyNeverInferOwnerOrSpringAuthorityProductRights() throws Exception {
        new IdentityJdbcTestEnvironment(root).withDatabase(db->db.boot.run(context->{
            var f=new AccountMetadataPostgresIT.Fixture(context);var owner=f.identity();var reader=f.identity();var account=f.account(AccountKind.ORGANIZATION);
            f.member(account,owner,true,"");f.member(account,reader,false,"example.reader");
            var object=UUID.randomUUID();var service=service(f,catalog(CATALOG),r->r.resource()!=null && r.resource().value().equals(object)?PermissionDecision.ALLOW:PermissionDecision.DENY);
            try {
                authenticate(owner);try(var scope=service.select(account.id())) {
                    var tenant=TenantContext.requireTenant();assertEquals(TenantId.of(account.tenant().value()),tenant);
                    assertEquals(PermissionDecision.ALLOW,service.evaluate(request(owner,tenant,AccountPermissions.INSPECT_ACCOUNT,ResourceReference.of(AccountId.class,account.id()))));
                    assertEquals(PermissionDecision.DENY,service.evaluate(request(owner,tenant,Rights.READ,ResourceReference.of(UUID.class,object))));
                }
                authenticate(reader);try(var scope=service.select(account.id())) {
                    var tenant=TenantContext.requireTenant();assertEquals(PermissionDecision.ALLOW,service.evaluate(request(reader,tenant,Rights.READ,ResourceReference.of(UUID.class,object))));
                    assertEquals(PermissionDecision.DENY,service.evaluate(request(reader,tenant,Rights.READ,ResourceReference.of(UUID.class,UUID.randomUUID()))));
                    assertEquals(PermissionDecision.DENY,service.evaluate(request(reader,tenant,AccountPermissions.MANAGE_MEMBER_ROLES,null)));
                    assertEquals(PermissionDecision.DENY,service.evaluate(request(owner,tenant,Rights.READ,ResourceReference.of(UUID.class,object))));
                }
                authenticate(owner);service.assignRoles(account.id(),reader,List.of("example.editor"));
                authenticate(reader);try(var scope=service.select(account.id())) {
                    assertEquals(PermissionDecision.DENY,service.evaluate(request(reader,TenantContext.requireTenant(),Rights.READ,ResourceReference.of(UUID.class,object))));
                    assertEquals(PermissionDecision.ALLOW,service.evaluate(request(reader,TenantContext.requireTenant(),Rights.EDIT,ResourceReference.of(UUID.class,object))));
                }
                SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("ROLE_ADMIN",null,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
                assertEquals(PermissionDecision.DENY,service.evaluate(request(reader,TenantId.of(account.tenant().value()),Rights.EDIT,ResourceReference.of(UUID.class,object))));
            } finally {SecurityContextHolder.clearContext();}
        }));
    }
    @Test void identityWideDisableRequiresSurvivingOwnersAcrossAllActiveAccounts() throws Exception {
        new IdentityJdbcTestEnvironment(root).withDatabase(db->db.boot.run(context->{
            var f=new AccountMetadataPostgresIT.Fixture(context);var user=f.identity();var other=f.identity();var sole=f.account(AccountKind.PERSONAL);var shared=f.account(AccountKind.ORGANIZATION);
            f.member(sole,user,true,"");f.member(shared,user,true,"");f.member(shared,other,true,"");var service=service(f,catalog(CATALOG),r->PermissionDecision.ALLOW);
            try {
                authenticate(user);assertEquals(AccountAccessException.Code.LAST_OWNER,assertThrows(AccountAccessException.class,service::disableIdentity).code());
                assertEquals(IdentityStatus.ACTIVE,f.writer.execute(status->context.getBean(IdentityRepository.class).byId(user).status()));
                service.closeAccount(sole.id());service.disableIdentity();
                assertEquals(IdentityStatus.DISABLED,f.writer.execute(status->context.getBean(IdentityRepository.class).byId(user).status()));
                assertEquals(AccountAccessException.Code.ACCESS_DENIED,assertThrows(AccountAccessException.class,()->service.select(shared.id())).code());
            }finally{SecurityContextHolder.clearContext();}
        }));
    }
    @Test void unknownPersistedRolesFailStartupAndUntrustedSelectionAndNestedWritesFailClosed() throws Exception {
        new IdentityJdbcTestEnvironment(root).withDatabase(db->db.boot.run(context->{
            var f=new AccountMetadataPostgresIT.Fixture(context);var user=f.identity();var account=f.account(AccountKind.ORGANIZATION);f.member(account,user,true,"example.reader");
            var service=service(f,catalog(CATALOG),r->PermissionDecision.ALLOW);
            assertEquals(AccountAccessException.Code.CONFIGURATION,assertThrows(AccountAccessException.class,()->service(f,catalog(CATALOG.replace("example.reader","example.removed")),r->PermissionDecision.ALLOW)).code());
            try {
                authenticate(user);assertEquals(AccountAccessException.Code.ACCESS_DENIED,assertThrows(AccountAccessException.class,()->service.select(AccountId.create())).code());
                try(var scope=service.select(account.id())) {assertEquals(AccountAccessException.Code.CONFIGURATION,assertThrows(AccountAccessException.class,service::createOrganization).code());}
                assertEquals(AccountAccessException.Code.INVALID_ROLES,assertThrows(AccountAccessException.class,()->service.assignRoles(account.id(),user,List.of("example.unknown"))).code());
            }finally{SecurityContextHolder.clearContext();}
        }));
    }
    @Test void ownershipTransferIsExplicitAndProductRoleAssignmentCannotPromoteAMember() throws Exception {
        new IdentityJdbcTestEnvironment(root).withDatabase(db->db.boot.run(context->{
            var f=new AccountMetadataPostgresIT.Fixture(context);var owner=f.identity();var next=f.identity();var account=f.account(AccountKind.ORGANIZATION);
            f.member(account,owner,true,"");f.member(account,next,false,"");var service=service(f,catalog(CATALOG),r->PermissionDecision.ALLOW);
            try {
                authenticate(owner);service.assignRoles(account.id(),next,List.of("example.editor"));
                assertEquals(Boolean.FALSE,f.writer.execute(status->context.getBean(AccountMembershipRepository.class).forKey(IdentityAccounts.key(account.id(),next)).orElseThrow().owner().enabled()));
                service.transferOwnership(account.id(),next);
                assertEquals(AccountAccessException.Code.ACCESS_DENIED,assertThrows(AccountAccessException.class,()->service.assignRoles(account.id(),next,List.of())).code());
                authenticate(next);assertTrue(service.accounts().getFirst().owner());
                assertEquals(AccountAccessException.Code.LAST_OWNER,assertThrows(AccountAccessException.class,()->service.removeMember(account.id(),next)).code());
            }finally{SecurityContextHolder.clearContext();}
        }));
    }
}
