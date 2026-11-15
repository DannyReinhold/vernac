// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.vernac.runtime.authorization.*;
import org.vernac.runtime.tenancy.*;
import org.vernac.saas.identity.domain.*;
import org.vernac.saas.identity.authorization.AccountPermissions;
import org.vernac.saas.identity.roles.*;
import org.vernac.saas.identity.security.*;
import org.vernac.saas.identity.persistence.domain.*;
import org.vernac.saas.identity.persistence.adapter.outbound.jdbc.*;
import static org.vernac.saas.identity.persistence.AccountAccessException.Code.*;

/** Explicit trusted global metadata boundary. Product tenant scope and object policy remain distinct. */
public final class IdentityAccounts implements PermissionEvaluator {
    public static final int MAX_ACCOUNTS_PER_USER=100, MAX_MEMBERS_PER_ACCOUNT=1000;
    private static final String S="\"org.vernac.saas.identity.persistence\".";
    private static final Set<String> OWNER_KEYS=Arrays.stream(AccountPermissions.values()).map(PermissionKey::qualifiedName).collect(java.util.stream.Collectors.toUnmodifiableSet());
    public record AccountView(AccountId account,AccountKind kind,boolean owner) {}
    private final NamedParameterJdbcTemplate jdbc;private final TransactionTemplate writer,read;
    private final JdbcIdentityCapacity budget;private final AccountRepository accounts;private final AccountMembershipRepository members;
    private final PersonalAccountRepository personal;private final IdentityRepository identities;
    private final IdentitySessionTrust trust;private final RoleCatalog catalog;private final Set<String> initial;
    private final PermissionEvaluator objectPolicy;private final Clock clock;

    public IdentityAccounts(NamedParameterJdbcTemplate jdbc,PlatformTransactionManager manager,Clock clock,
            IdentitySessionTrust trust,RoleCatalog catalog,Collection<String> initialRoles,PermissionEvaluator objectPolicy) {
        try {
            unscoped();this.jdbc=Objects.requireNonNull(jdbc);this.clock=Objects.requireNonNull(clock);this.trust=Objects.requireNonNull(trust);
            this.catalog=Objects.requireNonNull(catalog);this.initial=Set.copyOf(catalog.assignments(initialRoles));this.objectPolicy=Objects.requireNonNull(objectPolicy);
            if(!(manager instanceof DataSourceTransactionManager tx) || tx.getDataSource()!=jdbc.getJdbcTemplate().getDataSource())throw fail(CONFIGURATION);
            for(String role:catalog.roleKeys())if(catalog.permissions(List.of(role)).stream().anyMatch(key->OWNER_KEYS.contains(key.qualifiedName())))throw fail(CONFIGURATION);
            writer=new TransactionTemplate(manager);writer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);writer.setTimeout(5);
            read=new TransactionTemplate(manager);read.setReadOnly(true);read.setTimeout(5);
            budget=new JdbcIdentityCapacity(jdbc);accounts=new JdbcAccountRepository(jdbc);members=new JdbcAccountMembershipRepository(jdbc);
            personal=new JdbcPersonalAccountRepository(jdbc);identities=new JdbcIdentityRepository(jdbc);
            // Bounded result; never load every stored assignment into application memory.
            String unknown=catalog.roleKeys().isEmpty()?"\"roles\"<>''":"EXISTS (SELECT 1 FROM unnest(string_to_array(\"roles\",',')) AS k WHERE k<>'' AND k NOT IN (:known))";
            if(!jdbc.queryForList("SELECT 1 FROM "+S+"\"AccountMembership\" WHERE "+unknown+" LIMIT 1",Map.of("known",catalog.roleKeys()),Integer.class).isEmpty())throw fail(CONFIGURATION);
        } catch(RuntimeException unavailable) {throw fail(CONFIGURATION);}
    }
    static AccountMemberKey key(AccountId account,UserId user) {return AccountMemberKey.of(account.asString()+"/"+user.asString());}
    private static AccountAccessException fail(AccountAccessException.Code code) {return new AccountAccessException(code);}
    private static void unscoped() {
        if(TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.getResourceMap().isEmpty() || TenantContext.currentTenant().isPresent() || AuthorizationGuard.currentActor().isPresent())throw fail(CONFIGURATION);
    }
    private UserId currentUser() {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(!trust.current(authentication))throw fail(ACCESS_DENIED);
        return ((IdentityPrincipal)authentication.getPrincipal()).userId();
    }
    private <T>T mutate(Supplier<T> action) {
        unscoped();try {return Objects.requireNonNull(writer.execute(status->budget.withBudgetLock(action)));}
        catch(AccountAccessException safe){throw safe;}catch(RuntimeException unavailable){throw fail(UNAVAILABLE);}
    }
    private Account locked(AccountId id) {
        var rows=jdbc.queryForList("SELECT \"id\" FROM "+S+"\"Account\" WHERE \"id\"=:id FOR UPDATE",Map.of("id",id.value()),UUID.class);
        if(rows.isEmpty())throw fail(ACCESS_DENIED);var account=accounts.byId(id);if(account.status()!=AccountStatus.ACTIVE)throw fail(ACCESS_DENIED);return account;
    }
    private AccountMembership member(AccountId account,UserId user) {
        var row=members.forKey(key(account,user)).orElseThrow(()->fail(ACCESS_DENIED));
        if(row.status()!=AccountMemberStatus.ACTIVE || identities.existing(user).map(i->i.status()!=IdentityStatus.ACTIVE).orElse(true))throw fail(ACCESS_DENIED);
        checkedRoles(row);return row;
    }
    private Set<String> checkedRoles(AccountMembership row) {
        try{return Set.copyOf(catalog.assignments(RoleAssignments.decode(row.roles().encoded())));}
        catch(RuntimeException invalid){throw fail(CONFIGURATION);}
    }
    private void owner(AccountId account,UserId user) {if(!member(account,user).owner().enabled())throw fail(ACCESS_DENIED);}
    private long owners(AccountId account) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM "+S+"\"AccountMembership\" m JOIN "+S+"\"Identity\" i ON i.\"id\"=m.\"subject\" WHERE m.\"account\"=:account AND m.\"owner\" AND m.\"status\"='ACTIVE' AND i.\"status\"='ACTIVE'",Map.of("account",account.value()),Long.class));
    }
    private void room(UserId user) {
        long count=Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM "+S+"\"AccountMembership\" WHERE \"subject\"=:subject AND \"status\"='ACTIVE'",Map.of("subject",user.value()),Long.class));
        if(count>=MAX_ACCOUNTS_PER_USER)throw fail(CAPACITY);
    }
    /** Internal callback only; joins verification's one physical writer, never starts another. */
    AccountId provisionPersonal(UserId subject) {
        if(!TransactionSynchronizationManager.isActualTransactionActive() || TenantContext.currentTenant().isPresent() || AuthorizationGuard.currentActor().isPresent())throw fail(CONFIGURATION);
        return budget.withBudgetLock(()->{
            if(identities.existing(subject).map(i->i.status()!=IdentityStatus.ACTIVE).orElse(true))throw fail(ACCESS_DENIED);
            var existing=personal.existing(subject);
            if(existing.isPresent()) {
                var row=existing.get();if(!row.subject().equals(subject))throw fail(CONFIGURATION);
                var account=accounts.existing(row.account()).orElseThrow(()->fail(CONFIGURATION));
                var membership=members.forKey(key(account.id(),subject)).orElseThrow(()->fail(CONFIGURATION));
                if(account.kind()!=AccountKind.PERSONAL || !membership.owner().enabled())throw fail(CONFIGURATION);return account.id();
            }
            room(subject);var account=newAccount(subject,AccountKind.PERSONAL);var now=clock.instant();
            personal.save(PersonalAccount.reconstitute(subject,subject,account.id(),now,now,0));return account.id();
        });
    }
    private Account newAccount(UserId subject,AccountKind kind) {
        var account=accounts.save(Account.create(AccountTenantKey.of(UUID.randomUUID()),kind,AccountStatus.ACTIVE));
        members.save(AccountMembership.create(account.id(),subject,key(account.id(),subject),AccountOwner.of(true),AccountMemberStatus.ACTIVE,AccountRoleKeys.of(RoleAssignments.encode(initial))));
        return account;
    }
    public AccountId createOrganization() {return mutate(()->{var user=currentUser();room(user);return newAccount(user,AccountKind.ORGANIZATION).id();});}
    public List<AccountView> accounts() {
        unscoped();try{return Objects.requireNonNull(read.execute(status->{var user=currentUser();
            return jdbc.query("SELECT a.\"id\",a.\"kind\",m.\"owner\" FROM "+S+"\"AccountMembership\" m JOIN "+S+"\"Account\" a ON a.\"id\"=m.\"account\" WHERE m.\"subject\"=:user AND m.\"status\"='ACTIVE' AND a.\"status\"='ACTIVE' ORDER BY a.\"id\" LIMIT :limit",Map.of("user",user.value(),"limit",MAX_ACCOUNTS_PER_USER),(rs,n)->new AccountView(AccountId.of(rs.getObject(1,UUID.class)),AccountKind.valueOf(rs.getString(2)),rs.getBoolean(3)));}));}
        catch(AccountAccessException safe){throw safe;}catch(RuntimeException unavailable){throw fail(UNAVAILABLE);}
    }
    /** Validate the account/membership first; callers must close this scope and later permissions recheck membership. */
    public TenantContext.Scope select(AccountId id) {
        unscoped();try {var tenant=Objects.requireNonNull(read.execute(status->{var user=currentUser();var account=accounts.existing(id).orElseThrow(()->fail(ACCESS_DENIED));
            if(account.status()!=AccountStatus.ACTIVE)throw fail(ACCESS_DENIED);member(id,user);return TenantId.of(account.tenant().value());}));return TenantContext.openVerified(tenant);}
        catch(AccountAccessException safe){throw safe;}catch(RuntimeException unavailable){throw fail(UNAVAILABLE);}
    }
    public void assignRoles(AccountId id,UserId target,Collection<String> roles) {
        final String encoded;try{encoded=RoleAssignments.encode(catalog.assignments(roles));}catch(RuntimeException invalid){throw fail(INVALID_ROLES);}
        mutate(()->{var user=currentUser();locked(id);owner(id,user);var membership=member(id,target);membership.assign(AccountRoleKeys.of(encoded));members.save(membership);return true;});
    }
    public void removeMember(AccountId id,UserId target) {
        mutate(()->{var user=currentUser();locked(id);member(id,user);if(!user.equals(target))owner(id,user);
            var membership=members.forKey(key(id,target));if(membership.isEmpty() || membership.get().status()!=AccountMemberStatus.ACTIVE)return true;
            var row=membership.get();if(row.owner().enabled() && identities.existing(target).map(i->i.status()==IdentityStatus.ACTIVE).orElse(false) && owners(id)<=1)throw fail(LAST_OWNER);
            row.remove();members.save(row);return true;});
    }
    public void transferOwnership(AccountId id,UserId successor) {
        mutate(()->{var user=currentUser();locked(id);owner(id,user);if(user.equals(successor))return true;
            var next=member(id,successor);next.ownership(AccountOwner.of(true));members.save(next);
            var previous=member(id,user);previous.ownership(AccountOwner.of(false));members.save(previous);return true;});
    }
    public void closeAccount(AccountId id) {mutate(()->{var user=currentUser();var account=locked(id);owner(id,user);account.closeAccount();accounts.save(account);return true;});}
    public void disableIdentity() {endIdentity(false);}
    public void closeIdentity() {endIdentity(true);}
    private void endIdentity(boolean close) {
        mutate(()->{var user=currentUser();
            var owned=jdbc.queryForList("SELECT m.\"account\" FROM "+S+"\"AccountMembership\" m JOIN "+S+"\"Account\" a ON a.\"id\"=m.\"account\" WHERE m.\"subject\"=:user AND m.\"owner\" AND m.\"status\"='ACTIVE' AND a.\"status\"='ACTIVE' ORDER BY m.\"account\"",Map.of("user",user.value()),UUID.class);
            for(UUID account:owned){var id=AccountId.of(account);locked(id);if(owners(id)<=1)throw fail(LAST_OWNER);}
            var row=identities.byId(user);if(close)row.close();else row.disable();identities.save(row);return true;});
    }
    /** Pure fresh account/role policy, composed with the application's explicit object-specific policy. */
    @Override public PermissionDecision evaluate(PermissionRequest request) {
        try {
            var authentication=SecurityContextHolder.getContext().getAuthentication();
            if(!trust.current(authentication) || request.actor().kind()!=VerifiedActor.Kind.HUMAN || !IdentityActorResolver.REALM.equals(request.actor().realm()))return PermissionDecision.DENY;
            var user=((IdentityPrincipal)authentication.getPrincipal()).userId();if(!request.actor().subject().equals(user.asString()))return PermissionDecision.DENY;
            return Objects.requireNonNull(read.execute(status->{
                var found=accounts.forTenant(AccountTenantKey.of(request.tenant().value()));if(found.isEmpty() || found.get().status()!=AccountStatus.ACTIVE)return PermissionDecision.DENY;
                var account=found.get();var membership=members.forKey(key(account.id(),user));
                if(membership.isEmpty() || membership.get().status()!=AccountMemberStatus.ACTIVE || identities.existing(user).map(i->i.status()!=IdentityStatus.ACTIVE).orElse(true))return PermissionDecision.DENY;
                if(OWNER_KEYS.contains(request.permission().qualifiedName())) {
                    boolean resource=request.resource()==null || request.resource().type()==AccountId.class && request.resource().value().equals(account.id());
                    return membership.get().owner().enabled() && resource?PermissionDecision.ALLOW:PermissionDecision.DENY;
                }
                boolean grant=catalog.permissions(checkedRoles(membership.get())).contains(PermissionKey.of(request.permission().qualifiedName()));
                if(!grant)return PermissionDecision.DENY;return objectPolicy.evaluate(request)==PermissionDecision.ALLOW?PermissionDecision.ALLOW:PermissionDecision.DENY;
            }));
        }catch(RuntimeException unavailable){throw new AuthorizationException(AuthorizationException.Code.AUTHORIZATION_UNAVAILABLE);}
    }
    @Override public String toString(){return "IdentityAccounts[REDACTED]";}
}
