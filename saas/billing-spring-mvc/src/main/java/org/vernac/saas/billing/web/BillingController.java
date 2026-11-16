// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.billing.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.vernac.runtime.DomainValidationException;
import org.vernac.saas.identity.domain.AccountId;
import org.vernac.saas.identity.security.IdentityAccountException;
import org.vernac.saas.invoicing.*;
import org.vernac.saas.invoicing.domain.*;
import org.vernac.saas.mail.domain.MailAddress;
import org.vernac.saas.payments.domain.*;
import org.vernac.saas.payments.persistence.*;
import org.vernac.saas.payments.persistence.domain.*;
import org.vernac.saas.subscriptions.*;
import org.vernac.saas.subscriptions.domain.*;

import java.util.*;

/**
 * Small opt-in transport adapter. Every action delegates to freshly authorized application
 * services.
 */
@Controller
@RequestMapping("/billing")
public final class BillingController {
    private final BillingSelfService service;
    private final BillingPages pages;

    public BillingController(BillingSelfService service, BillingPages pages) {
        this.service = Objects.requireNonNull(service);
        this.pages = Objects.requireNonNull(pages);
    }

    @GetMapping
    public ResponseEntity<String> home(HttpServletRequest request) {
        noQuery(request);
        return html(pages.home(service.accounts(), context(request), csrf(request)));
    }

    @GetMapping("/accounts/{account}")
    public ResponseEntity<String> account(
            @PathVariable("account") String account, HttpServletRequest request) {
        requireKeys(request, Set.of("afterOrder", "afterInvoice"));
        return html(
                pages.account(
                        service.account(
                                account(account),
                                optional(request, "afterOrder")
                                        .map(v -> PaymentOrderId.of(uuid(v))),
                                optional(request, "afterInvoice").map(v -> InvoiceId.of(uuid(v)))),
                        context(request),
                        csrf(request)));
    }

    @GetMapping("/accounts/{account}/payments/{order}")
    public ResponseEntity<String> payment(
            @PathVariable("account") String account, @PathVariable("order") String order, HttpServletRequest request) {
        noQuery(request);
        return html(
                pages.payment(
                        service.payment(account(account), PaymentOrderId.of(uuid(order))),
                        context(request),
                        csrf(request)));
    }

    @GetMapping("/invoices/{invoice}")
    public ResponseEntity<byte[]> pdf(@PathVariable("invoice") String invoice, HttpServletRequest request) {
        noQuery(request);
        var id = InvoiceId.of(uuid(invoice));
        var view = service.invoice(id);
        var document = service.download(id);
        return document(
                Base64.getDecoder().decode(document.pdf().encoded()),
                MediaType.APPLICATION_PDF,
                view.content().number().text() + ".pdf");
    }

    @GetMapping("/invoices/{invoice}/xml")
    public ResponseEntity<byte[]> xml(@PathVariable("invoice") String invoice, HttpServletRequest request) {
        noQuery(request);
        var id = InvoiceId.of(uuid(invoice));
        var view = service.invoice(id);
        var document = service.download(id);
        return document(
                Base64.getDecoder().decode(document.xml().encoded()),
                MediaType.APPLICATION_XML,
                view.content().number().text() + ".xml");
    }

    @PostMapping("/start")
    public ResponseEntity<Void> start(HttpServletRequest request) {
        form(request, "account");
        var account = account(value(request, "account"));
        service.start(account);
        return back(request, account);
    }

    @PostMapping("/profile")
    public ResponseEntity<Void> profile(HttpServletRequest request) {
        form(request, "account", "name", "street", "postalCode", "city", "email");
        var account = account(value(request, "account"));
        service.profile(
                account,
                InvoiceBuyer.of(
                        InvoiceParty.of(
                                InvoiceText.of(value(request, "name")),
                                InvoiceText.of(value(request, "street")),
                                InvoicePostalCode.of(value(request, "postalCode")),
                                InvoiceText.of(value(request, "city"))),
                        MailAddress.of(value(request, "email")),
                        CustomerNumber.of("ACCOUNT-" + account.asString())));
        return back(request, account);
    }

    @PostMapping("/checkout")
    public ResponseEntity<Void> checkout(HttpServletRequest request) {
        form(request, "account", "operation", "profile", "plan", "revision");
        var account = account(value(request, "account"));
        var order =
                service.checkout(
                        account,
                        PaymentOperationId.of(uuid(value(request, "operation"))),
                        PaymentProfileKey.of(value(request, "profile")),
                        offer(request));
        return redirect(
                context(request)
                        + "/billing/accounts/"
                        + account.asString()
                        + "/payments/"
                        + order.id().asString());
    }

    @PostMapping("/subscription")
    public ResponseEntity<Void> subscription(HttpServletRequest request) {
        form(request, "account", "action", "plan", "revision");
        var account = account(value(request, "account"));
        var action = value(request, "action");
        service.subscription(
                account,
                action,
                action.equals("schedule") ? Optional.of(offer(request)) : Optional.empty());
        return back(request, account);
    }

    @PostMapping("/payment/{action:retry|close|reconcile}")
    public ResponseEntity<Void> paymentAction(
            @PathVariable("action") String action, HttpServletRequest request) {
        form(
                request,
                action.equals("reconcile")
                        ? new String[] {"account", "order", "remote"}
                        : new String[] {"account", "order"});
        var account = account(value(request, "account"));
        var order = PaymentOrderId.of(uuid(value(request, "order")));
        switch (action) {
            case "retry" -> service.retryPayment(account, order);
            case "close" -> service.closeUnpaid(account, order);
            case "reconcile" ->
                    service.reconcile(account, order, RemotePaymentId.of(value(request, "remote")));
            default -> throw new IllegalArgumentException();
        }
        return redirect(
                context(request)
                        + "/billing/accounts/"
                        + account.asString()
                        + "/payments/"
                        + order.asString());
    }

    @PostMapping("/{kind:invoice|mail}/retry")
    public ResponseEntity<Void> retry(@PathVariable("kind") String kind, HttpServletRequest request) {
        form(request, "account", "invoice");
        var account = account(value(request, "account"));
        var invoice = InvoiceId.of(uuid(value(request, "invoice")));
        if (kind.equals("invoice")) service.retryInvoice(account, invoice);
        else service.retryMail(account, invoice);
        return back(request, account);
    }

    @ExceptionHandler({
        IdentityAccountException.class,
        InvoiceFailure.class,
        PaymentProcessingFailure.class,
        SubscriptionFailure.class,
        DomainValidationException.class,
        IllegalArgumentException.class
    })
    public ResponseEntity<String> failure(RuntimeException failure) {
        boolean denied =
                failure instanceof IdentityAccountException identity
                                && identity.code() == IdentityAccountException.Code.ACCESS_DENIED
                        || failure instanceof InvoiceFailure invoice
                                && invoice.code() == InvoiceFailure.Code.ACCESS_DENIED
                        || failure instanceof PaymentProcessingFailure payment
                                && payment.code() == PaymentProcessingFailure.Code.ACCESS_DENIED;
        boolean unavailable =
                failure instanceof InvoiceFailure invoice
                                && (invoice.code() == InvoiceFailure.Code.CONFIGURATION
                                        || invoice.code() == InvoiceFailure.Code.UNAVAILABLE)
                        || failure instanceof PaymentProcessingFailure payment
                                && (payment.code() == PaymentProcessingFailure.Code.CONFIGURATION
                                        || payment.code()
                                                == PaymentProcessingFailure.Code.UNAVAILABLE)
                        || failure instanceof SubscriptionFailure subscription
                                && (subscription.code() == SubscriptionFailure.Code.CONFIGURATION
                                        || subscription.code()
                                                == SubscriptionFailure.Code.UNAVAILABLE)
                        || failure instanceof IdentityAccountException identity
                                && identity.code() == IdentityAccountException.Code.UNAVAILABLE;
        String message =
                denied
                        ? "Kein Zugriff auf dieses Konto oder Dokument."
                        : unavailable
                                ? "Abrechnung vorübergehend nicht verfügbar. Bitte später erneut"
                                        + " öffnen."
                                : failure instanceof InvoiceFailure invoice
                                                && invoice.code()
                                                        == InvoiceFailure.Code
                                                                .BILLING_PROFILE_REQUIRED
                                        ? "Bitte zuerst die vollständige Rechnungsadresse"
                                                + " speichern."
                                        : "Aktion derzeit nicht möglich. Bitte Eingaben und den"
                                                + " aktuellen Zahlungsstatus prüfen.";
        return ResponseEntity.status(denied ? 403 : unavailable ? 503 : 409)
                .headers(headers())
                .contentType(MediaType.TEXT_HTML)
                .body(pages.failure(message));
    }

    private static HttpHeaders headers() {
        var headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set(
                "Content-Security-Policy",
                "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri"
                        + " 'none'; frame-ancestors 'none'");
        return headers;
    }

    private static ResponseEntity<String> html(String body) {
        return ResponseEntity.ok().headers(headers()).contentType(MediaType.TEXT_HTML).body(body);
    }

    private static ResponseEntity<byte[]> document(byte[] body, MediaType type, String filename) {
        return ResponseEntity.ok()
                .headers(headers())
                .contentType(type)
                .header(
                        "Content-Disposition",
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(body);
    }

    private static ResponseEntity<Void> redirect(String path) {
        return ResponseEntity.status(303).headers(headers()).header("Location", path).build();
    }

    private static ResponseEntity<Void> back(HttpServletRequest request, AccountId account) {
        return redirect(context(request) + "/billing/accounts/" + account.asString());
    }

    private static String context(HttpServletRequest request) {
        return request.getContextPath();
    }

    private static CsrfToken csrf(HttpServletRequest request) {
        if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token)
            return token;
        throw new IllegalArgumentException();
    }

    private static void form(HttpServletRequest request, String... fields) {
        var keys = new HashSet<>(List.of(fields));
        keys.add(csrf(request).getParameterName());
        requireKeys(request, keys);
        if (request.getQueryString() != null
                || request.getContentType() == null
                || !request.getContentType()
                        .toLowerCase(Locale.ROOT)
                        .startsWith("application/x-www-form-urlencoded"))
            throw new IllegalArgumentException();
    }

    private static void requireKeys(HttpServletRequest request, Set<String> allowed) {
        for (var entry : request.getParameterMap().entrySet())
            if (!allowed.contains(entry.getKey()) || entry.getValue().length != 1)
                throw new IllegalArgumentException();
    }

    private static void noQuery(HttpServletRequest request) {
        if (request.getQueryString() != null) throw new IllegalArgumentException();
    }

    private static String value(HttpServletRequest request, String name) {
        return optional(request, name).orElseThrow(IllegalArgumentException::new);
    }

    private static Optional<String> optional(HttpServletRequest request, String name) {
        var values = request.getParameterValues(name);
        if (values == null) return Optional.empty();
        if (values.length != 1
                || values[0] == null
                || values[0].isBlank()
                || values[0].length() > 512) throw new IllegalArgumentException();
        return Optional.of(values[0]);
    }

    private static UUID uuid(String value) {
        var id = UUID.fromString(value);
        if (!id.toString().equals(value)) throw new IllegalArgumentException();
        return id;
    }

    private static AccountId account(String value) {
        return AccountId.of(uuid(value));
    }

    private static PlanCatalog.OfferId offer(HttpServletRequest request) {
        return new PlanCatalog.OfferId(
                PlanKey.of(value(request, "plan")), OfferRevision.of(value(request, "revision")));
    }
}
