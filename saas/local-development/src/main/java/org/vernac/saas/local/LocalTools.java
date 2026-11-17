// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.local;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.KeyStore;
import javax.net.ssl.*;
/** Local operator client. Trusts the exact retained localhost certificate, not arbitrary TLS peers. */
public final class LocalTools {
    private LocalTools() {}
    public static void main(String[] args) throws Exception {
        if(args.length==0||!java.util.Set.of("mail","status","refund").contains(args[0])||args[0].equals("refund")&&args.length!=5)
            throw new IllegalArgumentException("Use mail, status, or refund ACCOUNT_ID PAYMENT_ORDER_ID CENTS STABLE_OPERATION_UUID");
        var state=LocalState.read(Path.of(System.getProperty("user.dir")));
        var store=KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(state.store())){store.load(input,state.value("store").toCharArray());}
        var trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(store);
        var ssl=SSLContext.getInstance("TLS");ssl.init(null,trust.getTrustManagers(),null);
        var client=HttpClient.newBuilder().sslContext(ssl).connectTimeout(java.time.Duration.ofSeconds(5)).build();
        String path=args[0].equals("mail")?"/__local/mail":args[0].equals("status")?"/__operator/billing-status":"/__operator/refunds";
        var request=HttpRequest.newBuilder(URI.create("https://localhost:8443"+path)).timeout(java.time.Duration.ofSeconds(20))
            .header("Authorization","Bearer "+state.value(args[0].equals("mail")?"preview":"merchant"));
        if(!args[0].equals("mail")) {
            String body=args[0].equals("refund")?String.join("\n",args[1],args[2],args[4],args[3]):"";
            request.header("Content-Type","text/plain;charset=UTF-8").POST(HttpRequest.BodyPublishers.ofString(body));
        }
        var response=client.send(request.build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()<200||response.statusCode()>=300)throw new IllegalStateException("Local action not confirmed; retain the operation UUID for retries.");
        if(args[0].equals("mail"))for(var item:LocalState.JSON.readTree(response.body()))System.out.println(item.path("recipient").asString()+"\n"+item.path("text").asString()+"\n");
        else System.out.println(response.body());
    }
}
