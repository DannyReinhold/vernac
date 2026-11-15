// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import java.net.*;
import java.time.Clock;
import java.util.*;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.vernac.runtime.authorization.AuthorizationGuard;
import org.vernac.runtime.tenancy.TenantContext;
import org.vernac.saas.identity.domain.*;
import org.vernac.saas.identity.persistence.adapter.outbound.jdbc.JdbcIdentityLoginAddressRepository;
import org.vernac.saas.identity.persistence.domain.IdentityChallengePurpose;
import org.vernac.saas.identity.security.*;
import org.vernac.saas.identity.security.credentials.*;
import org.vernac.saas.mail.domain.MailProvider;
import static org.vernac.saas.identity.security.IdentityActionException.Code.*;

/** Explicit application assembly of neutral mail requests and atomic public credential/provisioning journeys. */
public final class IdentityJourneys implements IdentityBrowserActions {
    private static final class Rejected extends RuntimeException {}
    private final TransactionTemplate writer;private final NamedParameterJdbcTemplate jdbc;
    private final IdentityJdbcAuthentication authentication;private final JdbcIdentityAdmission admission;
    private final IdentityAccounts accounts;private final BoundedCredentialPasswords passwords;
    private final IdentityDeliveryCrypto crypto;private final JdbcIdentityMailRequests mail;
    private final JdbcIdentityChallenges challenges;private final JdbcIdentityRegistrations registrations;
    private final JdbcIdentityCredentials credentials;private final SmartLifecycle lifecycle;

    public IdentityJourneys(NamedParameterJdbcTemplate jdbc,PlatformTransactionManager manager,Clock clock,
            IdentityJdbcAuthentication authentication,IdentityAccounts accounts,BoundedCredentialPasswords passwords,
            MailProvider selectedProvider,URI trustedOrigin,boolean allowLoopbackSimulation,String activeKey,Map<String,byte[]> keyring) {
        try {
            boundary();this.jdbc=Objects.requireNonNull(jdbc);Objects.requireNonNull(clock);
            this.authentication=Objects.requireNonNull(authentication);this.accounts=Objects.requireNonNull(accounts);this.passwords=Objects.requireNonNull(passwords);
            if(!accounts.uses(jdbc,manager))throw new IllegalStateException();admission=authentication.admissionFor(jdbc,manager);
            var encryption=new JdbcIdentityEncryptionAdmission(jdbc,manager,clock);
            crypto=new IdentityDeliveryCrypto(activeKey,keyring,clock,encryption);
            var sender=new IdentityProviderMailSender(selectedProvider,trustedOrigin,allowLoopbackSimulation);
            encryption.initialize();
            writer=new TransactionTemplate(manager);writer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);writer.setTimeout(5);
            mail=new JdbcIdentityMailRequests(jdbc,clock,crypto);challenges=new JdbcIdentityChallenges(jdbc,clock);
            registrations=new JdbcIdentityRegistrations(jdbc,clock);credentials=new JdbcIdentityCredentials(jdbc);
            lifecycle=new IdentityMailPolling(new JdbcIdentityMailWorker(jdbc,manager,clock,crypto,sender),authentication::maintainAdmission);
        }catch(RuntimeException invalid){throw failure(UNAVAILABLE);}
    }
    /** Register this exact object as an explicit Spring lifecycle bean; no worker starts in the constructor. */
    public SmartLifecycle mailLifecycle(){return lifecycle;}
    static void boundary() {
        if(TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.getResourceMap().isEmpty() || TenantContext.currentTenant().isPresent() || AuthorizationGuard.currentActor().isPresent())throw failure(UNAVAILABLE);
    }
    private static IdentityActionException failure(IdentityActionException.Code code){return new IdentityActionException(code);}
    private static boolean admitted(JdbcIdentityAdmission.Decision decision) {
        if(decision.outcome()==JdbcIdentityAdmission.Outcome.GLOBAL_THROTTLED || decision.outcome()==JdbcIdentityAdmission.Outcome.SOURCE_THROTTLED)
            throw new IdentityActionException(THROTTLED,decision.retryAfterSeconds());
        return decision.admitted();
    }
    @Override public void requestRegistration(String address,InetAddress source){request(address,source,true);}
    @Override public void requestRecovery(String address,InetAddress source){request(address,source,false);}
    private void request(String address,InetAddress source,boolean registration) {
        boundary();try {
            LoginAddress parsed;try{parsed=LoginAddress.parse(address);}catch(CredentialInputException invalid){parsed=null;}
            if(!admitted(admission.mail(source,parsed)) || parsed==null)return;
            var permit=crypto.prepare();var recipient=parsed;
            writer.executeWithoutResult(status->{if(registration)mail.registration(recipient,permit);else mail.recovery(recipient,permit);});
        }catch(IdentityActionException safe){throw safe;}catch(RuntimeException unavailable){throw failure(UNAVAILABLE);}
    }
    @Override public boolean verify(String token,String password,InetAddress source){return consume(token,password,source,true);}
    @Override public boolean recover(String token,String password,InetAddress source){return consume(token,password,source,false);}
    private boolean consume(String input,String password,InetAddress source,boolean verification) {
        boundary();try {
            var token=IdentityChallengeToken.parse(input);
            if(!admitted(admission.challenge(source,token==null?null:token.locator().toString())) || token==null)return false;
            String encoded=passwords.encode(password); // bounded Spring work before the business writer
            return Boolean.TRUE.equals(writer.execute(status->challenges.consume(input,verification?IdentityChallengePurpose.VERIFICATION:IdentityChallengePurpose.RECOVERY,subject->{
                if(verification) {if(!registrations.complete(subject,encoded,accounts::provisionPersonal))throw new Rejected();}
                else {var snapshot=credentials.snapshot(subject).orElseThrow(Rejected::new);if(!credentials.replace(snapshot,encoded))throw new Rejected();}
            })));
        }catch(CredentialInputException invalid){throw failure(PASSWORD_POLICY);}
        catch(Rejected invalid){return false;}catch(IdentityActionException safe){throw safe;}catch(RuntimeException unavailable){throw failure(UNAVAILABLE);}
    }
    @Override public boolean changePassword(String oldPassword,String replacement,InetAddress source) {
        boundary();try {
            var global=authentication.requestAdmission().tryAcquire();if(!global.admitted())throw new IdentityActionException(THROTTLED,global.retryAfterSeconds());
            var current=SecurityContextHolder.getContext().getAuthentication();
            if(!authentication.sessionTrust().current(current))throw failure(AUTHENTICATION_REQUIRED);
            UserId user=((IdentityPrincipal)current.getPrincipal()).userId();var stamp=(IdentitySessionStamp)current.getDetails();
            var address=Objects.requireNonNull(writer.execute(status->new JdbcIdentityLoginAddressRepository(jdbc).existing(user))).orElseThrow(Rejected::new);
            if(!admitted(admission.login(source,LoginAddress.parse(address.deliveryAddress().encoded()))))return false;
            var snapshot=Objects.requireNonNull(writer.execute(status->credentials.snapshot(user))).orElseThrow(Rejected::new);
            if(snapshot.securityEpoch()!=stamp.epoch() || !passwords.matches(oldPassword,snapshot.encoded()))return false;
            String encoded=passwords.encode(replacement);
            return Boolean.TRUE.equals(writer.execute(status->credentials.replace(snapshot,encoded)));
        }catch(CredentialInputException invalid){throw failure(PASSWORD_POLICY);}
        catch(Rejected invalid){return false;}catch(IdentityActionException safe){throw safe;}catch(RuntimeException unavailable){throw failure(UNAVAILABLE);}
    }
    @Override public String toString(){return "IdentityJourneys[REDACTED]";}
}
