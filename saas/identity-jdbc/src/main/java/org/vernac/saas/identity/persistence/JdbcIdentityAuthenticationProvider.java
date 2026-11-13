// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.*;
import org.springframework.security.core.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.vernac.saas.identity.security.*;
import org.vernac.saas.identity.security.credentials.BoundedCredentialPasswords;
import org.vernac.saas.identity.persistence.adapter.outbound.jdbc.JdbcIdentityLoginAddressRepository;
import org.vernac.saas.identity.persistence.domain.IdentityLoginKey;
/** Explicit standard Spring provider, no endpoint/session creation/tenant selection or automatic registration. */
final class JdbcIdentityAuthenticationProvider implements AuthenticationProvider {
 static final class Throttled extends AuthenticationException {
  private final int retryAfterSeconds;
  Throttled(int seconds){super("Identity authentication temporarily throttled");retryAfterSeconds=Math.max(1,Math.min(86400,seconds));}
  int retryAfterSeconds(){return retryAfterSeconds;}
 }
 private final JdbcIdentityAdmission admission; private final BoundedCredentialPasswords passwords;
 private final JdbcIdentityCredentials credentials;private final JdbcIdentityLoginAddressRepository addresses;
 private final TransactionTemplate writer;private final Clock clock;
 JdbcIdentityAuthenticationProvider(NamedParameterJdbcTemplate jdbc,PlatformTransactionManager manager,
  JdbcIdentityAdmission admission,BoundedCredentialPasswords passwords,Clock clock) {
  this.admission=Objects.requireNonNull(admission);this.passwords=Objects.requireNonNull(passwords);this.clock=Objects.requireNonNull(clock);
  credentials=new JdbcIdentityCredentials(Objects.requireNonNull(jdbc));addresses=new JdbcIdentityLoginAddressRepository(jdbc);
  writer=new TransactionTemplate(Objects.requireNonNull(manager));
  writer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  writer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);writer.setTimeout(5);
 }
 @Override public boolean supports(Class<?> type){return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type);}
 @Override public Authentication authenticate(Authentication request) {
  if(!(request instanceof UsernamePasswordAuthenticationToken) || !(request.getPrincipal() instanceof IdentityLoginInput input))return null;
  try{
   if(request.isAuthenticated() || TransactionSynchronizationManager.isActualTransactionActive()
    || TransactionSynchronizationManager.isSynchronizationActive() || !TransactionSynchronizationManager.getResourceMap().isEmpty()
    || org.vernac.runtime.tenancy.TenantContext.currentTenant().isPresent()
    || org.vernac.runtime.authorization.AuthorizationGuard.currentActor().isPresent())
    throw unavailable();
   if(!(request.getCredentials() instanceof String raw))throw invalid();
   var decision=admission.login(input.source(),input.address());
   if(decision.outcome()==JdbcIdentityAdmission.Outcome.GLOBAL_THROTTLED
    || decision.outcome()==JdbcIdentityAdmission.Outcome.SOURCE_THROTTLED)throw new Throttled(decision.retryAfterSeconds());
   if(!decision.admitted())throw invalid();
   Optional<JdbcIdentityCredentials.Snapshot> snapshot=Objects.requireNonNull(writer.execute(status->
    addresses.forKey(IdentityLoginKey.of(input.address().loginKey())).flatMap(row->credentials.snapshot(row.id()))));
   if(snapshot.isEmpty()){passwords.performDummyVerification(raw);throw invalid();}
   var expected=snapshot.get();
   if(!passwords.matches(raw,expected.encoded()))throw invalid();
   if(!Boolean.TRUE.equals(writer.execute(status->credentials.confirm(expected))))throw invalid();
   var authenticated=UsernamePasswordAuthenticationToken.authenticated(new IdentityPrincipal(expected.subject()),null,List.of());
   authenticated.setDetails(new IdentitySessionStamp(expected.securityEpoch(),clock.instant()));
   return authenticated;
  }catch(BadCredentialsException | Throttled safe){throw safe;}
   catch(RuntimeException failure){throw unavailable();}
   finally{if(request instanceof CredentialsContainer credentialsContainer)credentialsContainer.eraseCredentials();}
 }
 private static BadCredentialsException invalid(){return new BadCredentialsException("Identity authentication failed");}
 private static AuthenticationServiceException unavailable(){return new AuthenticationServiceException("Identity authentication unavailable");}
 @Override public String toString(){return "JdbcIdentityAuthenticationProvider[REDACTED]";}
}
