// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.mail.brevo;
import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.vernac.saas.mail.domain.*;
import tools.jackson.databind.json.JsonMapper;
class BrevoMailTransportTest {
 static OutboundMail message(){return OutboundMail.of(MailAddress.of("recipient@example.test"),MailSubject.of("Synthetic subject"),
  MailBody.of("private body"),MailBody.of("<p>private HTML</p>"),MailCorrelationId.create());}
 static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-13T12:00:00Z"),ZoneOffset.UTC);
 static final class Server implements AutoCloseable {
  final HttpServer server;final ExecutorService threads=Executors.newCachedThreadPool();
  Server(HttpHandler handler)throws Exception {
   server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
   server.createContext("/v3/smtp/email",handler);server.setExecutor(threads);server.start();
  }
  URI uri(){return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/v3/smtp/email");}
  public void close(){server.stop(0);threads.shutdownNow();}
 }
 @Test void standardBrevoRequestHasOneRecipientAndNeverReturnsProviderBody() throws Exception {
  var captured=new AtomicReference<byte[]>();var header=new AtomicReference<String>();
  try(var server=new Server(exchange->{
   captured.set(exchange.getRequestBody().readAllBytes());header.set(exchange.getRequestHeaders().getFirst("api-key"));
   byte[] body="synthetic-provider-secret".getBytes();exchange.sendResponseHeaders(201,body.length);
   exchange.getResponseBody().write(body);exchange.close();
  });var provider=new BrevoMailTransport("synthetic-key-not-real","sender@example.test",server.uri(),CLOCK,Duration.ofSeconds(2))) {
   var mail=message();var outcome=provider.submit(mail);assertEquals(MailAcceptance.ACCEPTED,outcome.acceptance());
   var json=JsonMapper.builder().build().readTree(captured.get());
   assertEquals("sender@example.test",json.at("/sender/email").asText());assertEquals(1,json.get("to").size());
   assertEquals(mail.recipient().encoded(),json.at("/to/0/email").asText());
   assertEquals(mail.text().text(),json.get("textContent").asText());assertEquals(mail.html().text(),json.get("htmlContent").asText());
   assertEquals(mail.correlation().value().toString(),json.at("/headers/X-Vernac-Correlation").asText());
   assertEquals("synthetic-key-not-real",header.get());assertFalse(outcome.toString().contains("synthetic-provider-secret"));
   assertEquals("BrevoMailTransport[REDACTED]",provider.toString());
  }
 }
 @Test void retryHintsPermanentFailuresAndRedirectsStayWithinThePortContract() throws Exception {
  var status=new AtomicInteger(429);var retry=new AtomicReference<>("120");AtomicInteger requests=new AtomicInteger();
  try(var server=new Server(exchange->{
   requests.incrementAndGet();exchange.getRequestBody().readAllBytes();
   exchange.getResponseHeaders().add("Retry-After",retry.get());
   exchange.getResponseHeaders().add("Location","http://127.0.0.1:1/never-follow");
   exchange.sendResponseHeaders(status.get(),-1);exchange.close();
  });var provider=new BrevoMailTransport("synthetic-key-not-real","sender@example.test",server.uri(),CLOCK,Duration.ofSeconds(2))) {
   assertEquals(120,provider.submit(message()).retryAfter().seconds());
   retry.set("9999999999");assertEquals(1800,provider.submit(message()).retryAfter().seconds());
   status.set(503);retry.set("Fri, 13 Nov 2026 12:10:00 GMT");
   assertEquals(600,provider.submit(message()).retryAfter().seconds());
   retry.set("invalid synthetic value");assertEquals(60,provider.submit(message()).retryAfter().seconds());
   status.set(400);assertEquals(MailAcceptance.REJECTED,provider.submit(message()).acceptance());
   status.set(302);assertEquals(MailAcceptance.REJECTED,provider.submit(message()).acceptance());
   assertEquals(6,requests.get());
  }
 }
 @Test void fullCallTimeoutAndParallelAdmissionAreBounded() throws Exception {
  var release=new CountDownLatch(1);var entered=new CountDownLatch(4);
  try(var server=new Server(exchange->{
   exchange.getRequestBody().readAllBytes();entered.countDown();
   try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
   exchange.close();
  });var provider=new BrevoMailTransport("synthetic-key-not-real","sender@example.test",server.uri(),CLOCK,Duration.ofMillis(500));
      var pool=Executors.newFixedThreadPool(4)) {
   List<Future<MailSubmission>> calls=new ArrayList<>();long began=System.nanoTime();
   for(int i=0;i<4;i++)calls.add(pool.submit(()->provider.submit(message())));
   assertTrue(entered.await(3,TimeUnit.SECONDS));assertEquals(MailAcceptance.RETRY,provider.submit(message()).acceptance());
   for(var call:calls)assertEquals(MailAcceptance.RETRY,call.get(3,TimeUnit.SECONDS).acceptance());
   assertTrue(Duration.ofNanos(System.nanoTime()-began).compareTo(Duration.ofSeconds(4))<0);
   release.countDown();
  } finally{release.countDown();}
 }
 @Test void invalidConfigurationAndNonLoopbackTestTargetsAreRejectedWithoutSecrets() {
  for(String key:List.of("","synthetic-key\r\nInjected: true")) {
   var error=assertThrows(IllegalStateException.class,()->new BrevoMailTransport(key,"sender@example.test"));
   assertNull(error.getCause());assertEquals("BREVO_MAIL_CONFIGURATION_INVALID",error.getMessage());
  }
  assertThrows(IllegalStateException.class,()->new BrevoMailTransport("synthetic-key-not-real","sender@example.test",
   URI.create("http://example.test/v3/smtp/email"),CLOCK,Duration.ofSeconds(1)));
  assertThrows(IllegalStateException.class,()->new BrevoMailTransport("synthetic-key-not-real","sender@example.test",
   URI.create("http://127.0.0.1:1/v3/smtp/email"),CLOCK,Duration.ofNanos(1)));
 }
}
