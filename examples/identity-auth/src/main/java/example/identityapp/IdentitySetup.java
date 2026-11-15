// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package example.identityapp;
import java.net.URI;
import java.time.Clock;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.session.jdbc.config.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.vernac.runtime.application.AdapterBindings;
import org.vernac.runtime.authorization.*;
import org.vernac.runtime.authorization.spring.VernacAuthorizationConfiguration;
import org.vernac.saas.identity.domain.IdentityDirectory;
import org.vernac.saas.identity.persistence.*;
import org.vernac.saas.identity.roles.RoleCatalog;
import org.vernac.saas.identity.security.*;
import org.vernac.saas.identity.security.credentials.*;
import org.vernac.saas.mail.domain.MailProvider;
import example.identityapp.authorization.WorkspacePermissions;

@Configuration(proxyBeanMethods=false)
@Import({IdentityJdbcSessions.class,VernacAuthorizationConfiguration.class})
public class IdentitySetup {
 @Bean @Primary DataSource dataSource(Environment e){
  var pool=new com.zaxxer.hikari.HikariDataSource();pool.setJdbcUrl(e.getRequiredProperty("spring.datasource.url"));
  pool.setUsername(e.getRequiredProperty("spring.datasource.username"));pool.setPassword(e.getRequiredProperty("spring.datasource.password"));
  pool.setMaximumPoolSize(8);pool.setMinimumIdle(0);pool.setConnectionTimeout(2000);return pool;
 }
 @Bean @Primary PlatformTransactionManager transactionManager(@Qualifier("dataSource") DataSource dataSource){return new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);}

 @Bean Clock identityClock(){return Clock.systemUTC();}
 @Bean(destroyMethod="close") BoundedCredentialPasswords credentialWork(){
  return new BoundedCredentialPasswords(new CredentialPasswords(OfflinePasswordBlocklist.bundled()));
 }
 static byte[] key(Environment e,String name) {
  try{byte[] bytes=Base64.getDecoder().decode(e.getRequiredProperty(name));if(bytes.length!=32)throw new IllegalArgumentException();return bytes;}
  catch(RuntimeException invalid){throw new IllegalStateException("Identity key configuration unavailable");}
 }
 @Bean @DependsOn(AdapterBindings.INITIALIZATION)
 IdentityJdbcAuthentication identityAuthentication(NamedParameterJdbcTemplate jdbc,
  @Qualifier("transactionManager") PlatformTransactionManager writer,Clock clock,BoundedCredentialPasswords passwords,Environment e) {
  return IdentityJdbcAuthentication.initializeDefaultPolicy(jdbc,writer,clock,e.getRequiredProperty("identity.admission-key-label"),
   new SecretKeySpec(key(e,"identity.admission-key"),"HmacSHA256"),passwords);
 }
 @Bean org.springframework.security.authentication.AuthenticationManager browserAuthenticationManager(IdentityJdbcAuthentication authentication){return authentication.authenticationManager();}
 @Bean RoleCatalog roleCatalog(){
  return RoleCatalog.fromClasspath(getClass().getClassLoader(),"roles.json",List.of(WorkspacePermissions.values()));
 }
 @Bean IdentityAccounts identityAccounts(NamedParameterJdbcTemplate jdbc,@Qualifier("transactionManager") PlatformTransactionManager writer,
  Clock clock,IdentityJdbcAuthentication authentication,RoleCatalog catalog) {
  // This demonstration has one workspace-wide operation and no resource-specific authorization.
  PermissionEvaluator objects=request->request.permission().qualifiedName().equals(WorkspacePermissions.VIEW.qualifiedName())
   && request.resource()==null ? PermissionDecision.ALLOW:PermissionDecision.DENY;
  return new IdentityAccounts(jdbc,writer,clock,authentication.sessionTrust(),catalog,List.of("example.workspace_reader"),objects);
 }
 @Bean IdentityJourneys identityJourneys(NamedParameterJdbcTemplate jdbc,@Qualifier("transactionManager") PlatformTransactionManager writer,
  Clock clock,IdentityJdbcAuthentication authentication,IdentityAccounts accounts,BoundedCredentialPasswords passwords,MailProvider provider,Environment e) {
  String label=e.getRequiredProperty("identity.delivery-key-label");
  return new IdentityJourneys(jdbc,writer,clock,authentication,accounts,passwords,provider,URI.create(e.getRequiredProperty("identity.origin")),
   e.getProperty("identity.allow-loopback-simulation",Boolean.class,false),label,Map.of(label,key(e,"identity.delivery-key")));
 }
 @Bean SmartLifecycle identityMailWorker(IdentityJourneys journeys){return journeys.mailLifecycle();}
 @Bean ActorResolver identityActors(IdentityJdbcAuthentication authentication){return new SessionIdentityActorResolver(authentication.sessionTrust());}
 @Bean(destroyMethod="") @SpringSessionDataSource @DependsOn(AdapterBindings.INITIALIZATION)
 DataSource identitySessionDataSource(@Qualifier("dataSource") DataSource dataSource){return dataSource;}
 @Bean @SpringSessionTransactionManager
 PlatformTransactionManager identitySessionWriter(@Qualifier("transactionManager") PlatformTransactionManager writer){return writer;}
 @Bean SecurityFilterChain identityBrowser(HttpSecurity http,IdentityJdbcAuthentication authentication,IdentityJourneys journeys,IdentityAccounts accounts)throws Exception {
  return IdentityBrowserSecurity.build(http,authentication.authenticationManager(),authentication.sessionTrust(),new IdentityLocalReturnTarget("/workspace"),authentication.requestAdmission(),journeys,accounts);
 }
}
