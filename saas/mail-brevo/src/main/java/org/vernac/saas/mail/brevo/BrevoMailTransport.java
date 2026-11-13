// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.mail.brevo;
import java.io.IOException;
import java.net.URI;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;
import tools.jackson.databind.json.JsonMapper;
import org.vernac.saas.mail.domain.*;
/** Brevo API transport. No startup call, fallback, attachment, redirect or response logging. */
public final class BrevoMailTransport implements AutoCloseable {
 private static final URI ENDPOINT=URI.create("https://api.brevo.com/v3/smtp/email");
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private final OkHttpClient client; private final URI endpoint; private final String apiKey;
 private final MailAddress sender; private final Clock clock;
 private final Semaphore permits=new Semaphore(4);
 public BrevoMailTransport(String apiKey,String sender){this(apiKey,sender,ENDPOINT,Clock.systemUTC(),Duration.ofSeconds(10));}
 // Package-private local-server test seam, no configurable production URL.
 BrevoMailTransport(String apiKey,String sender,URI endpoint,Clock clock,Duration timeout) {
  try {
   if(apiKey==null || apiKey.isBlank() || apiKey.length()>4096 || apiKey.chars().anyMatch(c->c<=32 || c>=127)
    || timeout==null || timeout.toMillis()<1 || timeout.compareTo(Duration.ofSeconds(10))>0)throw new IllegalArgumentException();
   if(!endpoint.equals(ENDPOINT) && !(endpoint.getScheme().equals("http") && endpoint.getHost().equals("127.0.0.1")
    && endpoint.getRawQuery()==null && endpoint.getRawUserInfo()==null))throw new IllegalArgumentException();
   this.sender=MailAddress.of(sender);this.apiKey=apiKey;this.endpoint=endpoint;this.clock=Objects.requireNonNull(clock);
   client=new OkHttpClient.Builder().callTimeout(timeout.toMillis(),TimeUnit.MILLISECONDS)
    .connectTimeout(3,TimeUnit.SECONDS).readTimeout(5,TimeUnit.SECONDS).writeTimeout(5,TimeUnit.SECONDS)
    .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
    .addNetworkInterceptor(chain -> {
     var response=chain.proceed(chain.request());
     String retry=response.header("Retry-After");
     // OkHttp follows 503/zero independently of connection-retry configuration.
     // Floor zero to one second; only the durable caller may initiate another call.
     if(response.code()==503 && retry!=null && retry.matches("0+"))
      return response.newBuilder().header("Retry-After","1").build();
     return response;
    }).build();
  } catch(RuntimeException invalid){throw new IllegalStateException("BREVO_MAIL_CONFIGURATION_INVALID");}
 }
 public MailSubmission submit(OutboundMail mail) {
  if(!permits.tryAcquire())return result(MailAcceptance.RETRY,60);
  try {
   Objects.requireNonNull(mail);
   byte[] body=JSON.writeValueAsBytes(Map.of("sender",Map.of("email",sender.encoded()),
    "to",List.of(Map.of("email",mail.recipient().encoded())),"subject",mail.subject().text(),
    "textContent",mail.text().text(),"htmlContent",mail.html().text(),
    "headers",Map.of("X-Vernac-Correlation",mail.correlation().value().toString())));
   if(body.length>128*1024)return result(MailAcceptance.REJECTED,0);
   var request=new Request.Builder().url(endpoint.toString()).header("api-key",apiKey)
    .header("Accept","application/json").post(RequestBody.create(body,MediaType.get("application/json; charset=utf-8"))).build();
   try(var response=client.newCall(request).execute()) {
    int code=response.code();
    if(code==201)return result(MailAcceptance.ACCEPTED,0);
    if(code==408 || code==429 || code>=500)return result(MailAcceptance.RETRY,retryAfter(response.header("Retry-After")));
    return result(MailAcceptance.REJECTED,0);
   }
  } catch(IOException|RuntimeException unavailable){return result(MailAcceptance.RETRY,60);}
  finally {permits.release();}
 }
 private int retryAfter(String header) {
  if(header==null || header.length()>128)return 60;
  try {
   long seconds=header.matches("[0-9]{1,10}")?Long.parseLong(header):Duration.between(clock.instant(),
    ZonedDateTime.parse(header,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).getSeconds();
   return (int)Math.max(1,Math.min(1800,seconds));
  } catch(RuntimeException invalid){return 60;}
 }
 private static MailSubmission result(MailAcceptance status,int retry){return MailSubmission.of(status,MailRetryDelay.of(retry));}
 @Override public void close(){client.dispatcher().cancelAll();client.connectionPool().evictAll();client.dispatcher().executorService().shutdown();}
 @Override public String toString(){return "BrevoMailTransport[REDACTED]";}
}
