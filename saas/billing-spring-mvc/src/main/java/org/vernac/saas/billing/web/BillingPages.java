// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.billing.web;

import org.springframework.security.web.csrf.CsrfToken;
import org.vernac.saas.identity.security.IdentityAccountActions;
import org.vernac.saas.invoicing.domain.*;
import org.vernac.saas.payments.domain.*;
import org.vernac.saas.payments.persistence.domain.*;
import org.vernac.saas.subscriptions.*;
import org.vernac.saas.subscriptions.domain.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Escaped, script-free default pages; apps may provide their own transport using
 * BillingSelfService.
 */
public final class BillingPages {
    private final BillingSettings settings;

    public BillingPages(BillingSettings settings) {
        this.settings = Objects.requireNonNull(settings);
    }

    public String home(
            List<IdentityAccountActions.AccountView> accounts, String context, CsrfToken csrf) {
        var body =
                new StringBuilder(
                        "<h1>Billing</h1><p>Manage plans, payments and invoices"
                                + " for each workspace.</p><ul>");
        for (var account : accounts)
            if (account.owner())
                body.append("<li>")
                        .append(
                                link(
                                        context
                                                + "/billing/accounts/"
                                                + account.account().asString(),
                                        account.kind().equals("PERSONAL")
                                                ? "Personal workspace"
                                                : "Organization · "
                                                        + account.account()
                                                                .asString()
                                                                .substring(0, 8)))
                        .append("</li>");
        body.append("</ul><p>")
                .append(link(context + "/auth/accounts", "Workspaces and members"))
                .append("</p>");
        return shell(body.toString());
    }

    public String account(BillingSelfService.AccountPage model, String context, CsrfToken csrf) {
        String account = model.account().asString();
        var body =
                new StringBuilder("<h1>Plan and billing</h1><p>")
                        .append(link(context + settings.productPath(), "Back to the application"))
                        .append(" · ")
                        .append(link(context + "/billing", "Switch workspace"))
                        .append("</p>");
        if (model.subscription().isEmpty())
            body.append(
                            "<section><h2>Start with Free</h2><p>Get started without"
                                    + " payment details.</p>")
                    .append(
                            form(
                                    context,
                                    "/billing/start",
                                    csrf,
                                    hidden("account", account),
                                    "Activate Free"))
                    .append("</section>");
        else {
            var current = model.subscription().get();
            body.append("<section><h2>Current plan</h2><p><strong>")
                    .append(escape(current.terms().plan().value()))
                    .append("</strong> · ")
                    .append(money(current.terms().price()))
                    .append(" / ")
                    .append(interval(current.terms()))
                    .append("</p><p>")
                    .append(subscription(current.status()))
                    .append(" · ")
                    .append(
                            current.renewal() == RenewalStatus.CANCELLED
                                    ? "Renewal cancelled"
                                    : "Renewal enabled")
                    .append("</p><p>Current period: ")
                    .append(escape(current.period().start().toString()))
                    .append(" to ")
                    .append(escape(current.period().end().toString()))
                    .append("</p>")
                    .append(benefits(current.terms()));
            if (!current.renewalTerms().equals(current.terms()))
                body.append("<p>Next period: ")
                        .append(escape(current.renewalTerms().plan().value()))
                        .append(" · ")
                        .append(money(current.renewalTerms().price()))
                        .append("</p>");
            body.append(
                    form(
                            context,
                            "/billing/subscription",
                            csrf,
                            hidden("account", account) + hidden("action", "cancel"),
                            "Cancel renewal"));
            body.append(
                    form(
                            context,
                            "/billing/subscription",
                            csrf,
                            hidden("account", account) + hidden("action", "resume"),
                            "Resume renewal"));
            body.append(
                            form(
                                    context,
                                    "/billing/subscription",
                                    csrf,
                                    hidden("account", account) + hidden("action", "reopen"),
                                    "Restart on Free after expiry"))
                    .append("</section>");
        }
        var buyer = model.buyer();
        var party = buyer.map(InvoiceBuyer::party);
        body.append(
                "<section><h2>Billing address</h2><p>Enter a billing address in Germany."
                        + " Changes apply to future invoices.</p>");
        String fields =
                hidden("account", account)
                        + input(
                                "name",
                                "Name or company",
                                party.map(p -> p.name().text()).orElse(""))
                        + input(
                                "street",
                                "Street and house number",
                                party.map(p -> p.street().text()).orElse(""))
                        + input(
                                "postalCode",
                                "Postal code",
                                party.map(p -> p.postalCode().text()).orElse(""))
                        + input("city", "City", party.map(p -> p.city().text()).orElse(""))
                        + input("email", "Email", buyer.map(b -> b.contact().encoded()).orElse(""));
        body.append(form(context, "/billing/profile", csrf, fields, "Save billing address"))
                .append(
                        "</section><section><h2>Available plans</h2><p>Prices are in EUR"
                            + " and include applicable VAT."
                            + " Paid plan changes take effect after payment is confirmed.</p>");
        for (var offer : model.offers()) {
            String offerFields =
                    hidden("account", account)
                            + hidden("plan", offer.plan().value())
                            + hidden("revision", offer.revision().value());
            body.append("<article><h3>")
                    .append(escape(offer.plan().value()))
                    .append("</h3><p>")
                    .append(money(offer.price()))
                    .append(" / ")
                    .append(interval(offer))
                    .append("</p>")
                    .append(benefits(offer));
            if (model.subscription().isPresent()) {
                body.append(
                        form(
                                context,
                                "/billing/subscription",
                                csrf,
                                offerFields + hidden("action", "schedule"),
                                "Schedule for next period"));
                if (offer.price().value() > 0) {
                    String choices = "<label>Payment method <select name=\"profile\">";
                    for (var payment : settings.payments())
                        choices +=
                                "<option value=\""
                                        + escape(payment.profile().value())
                                        + "\">"
                                        + escape(payment.label())
                                        + (payment.test() ? " · TEST / no real payment" : "")
                                        + "</option>";
                    choices += "</select></label>";
                    body.append(
                            form(
                                    context,
                                    "/billing/checkout",
                                    csrf,
                                    offerFields
                                            + hidden("operation", UUID.randomUUID().toString())
                                            + choices,
                                    "Order and pay"));
                }
            }
            body.append("</article>");
        }
        body.append("</section><section><h2>Payments</h2><ul>");
        for (var order : model.orders())
            body.append("<li>")
                    .append(
                            link(
                                    context
                                            + "/billing/accounts/"
                                            + account
                                            + "/payments/"
                                            + order.id().asString(),
                                    order.terms().plan().value()
                                            + " · "
                                            + money(order.terms().price())
                                            + " · "
                                            + paymentStatus(order.status())))
                    .append("</li>");
        body.append("</ul>");
        if (model.orders().size() == 20)
            body.append(
                    link(
                            context
                                    + "/billing/accounts/"
                                    + account
                                    + "?afterOrder="
                                    + model.orders().getLast().id().asString(),
                            "More payments"));
        body.append("</section><section><h2>Invoices and corrections</h2><ul>");
        for (var invoice : model.invoices()) {
            String id = invoice.id().asString();
            body.append("<li>")
                    .append(escape(invoice.content().number().text()))
                    .append(" · ")
                    .append(
                            invoice.content().kind() == InvoiceKind.CORRECTION
                                    ? "Correction"
                                    : "Invoice")
                    .append(
                            invoice.content().environment() == InvoiceEnvironment.TEST
                                    ? " · TEST"
                                    : "")
                    .append(" · ")
                    .append(money(invoice.content().amounts().gross()));
            if (invoice.status() == InvoiceWorkStatus.READY)
                body.append(" · ")
                        .append(link(context + "/billing/invoices/" + id, "PDF / ZUGFeRD"))
                        .append(" · ")
                        .append(link(context + "/billing/invoices/" + id + "/xml", "XML"));
            else body.append(" · Preparing document");
            body.append(
                            form(
                                    context,
                                    "/billing/invoice/retry",
                                    csrf,
                                    hidden("account", account) + hidden("invoice", id),
                                    "Retry document processing"))
                    .append(
                            form(
                                    context,
                                    "/billing/mail/retry",
                                    csrf,
                                    hidden("account", account) + hidden("invoice", id),
                                    "Retry notification"))
                    .append("</li>");
        }
        body.append("</ul>");
        if (model.invoices().size() == 20)
            body.append(
                    link(
                            context
                                    + "/billing/accounts/"
                                    + account
                                    + "?afterInvoice="
                                    + model.invoices().getLast().id().asString(),
                            "More invoices"));
        body.append(
                "<p>Email acceptance does not confirm delivery. Your documents remain"
                        + " available here.</p></section>");
        return shell(body.toString());
    }

    public String payment(BillingSelfService.PaymentPage model, String context, CsrfToken csrf) {
        var order = model.order();
        String account = model.account().asString();
        String fields = hidden("account", account) + hidden("order", order.id().asString());
        var body =
                new StringBuilder("<h1>Payment</h1><p>")
                        .append(escape(order.terms().plan().value()))
                        .append(" · ")
                        .append(money(order.terms().price()))
                        .append("</p><p>")
                        .append(paymentStatus(order.status()))
                        .append(
                                "</p><p>Returning from checkout does not confirm payment."
                                        + " We update the status once the provider"
                                        + " confirms the outcome.</p>");
        if (order.checkout().isPresent())
            body.append("<p>")
                    .append(link(order.checkout().get().encoded(), "Open provider checkout"))
                    .append("</p>");
        if (order.status() == PaymentOrderStatus.REVIEW || order.reason() != PaymentReason.NONE)
            body.append(
                    "<p>This payment needs review. Please wait for the outcome"
                        + " before placing another order. The existing payment"
                        + " remains on record.</p>");
        body.append(
                        form(
                                context,
                                "/billing/payment/retry",
                                csrf,
                                fields,
                                "Retry failed payment"))
                .append(
                        form(
                                context,
                                "/billing/payment/close",
                                csrf,
                                fields,
                                "Close unpaid order"));
        body.append(
                        "<details><summary>Match an existing payment</summary><p>Verify an"
                            + " existing payment reference. This will not initiate"
                            + " a new payment.</p>")
                .append(
                        form(
                                context,
                                "/billing/payment/reconcile",
                                csrf,
                                fields + input("remote", "Payment reference", ""),
                                "Check reference"))
                .append("</details><p>")
                .append(link(context + "/billing/accounts/" + account, "Back to billing"))
                .append("</p>");
        body.append("<p>").append(link(context + "/billing/accounts/" + account + "/payments/" + order.id().asString(), "Refresh status")).append("</p>");
        return shell(body.toString());
    }

    public String failure(String message) {
        return shell(
                "<h1>Billing</h1><p role=\"alert\">"
                        + escape(message)
                        + "</p><p>Please reopen the previous page.</p>");
    }

    private String shell(String body) {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta"
                + " name=\"viewport\""
                + " content=\"width=device-width,initial-scale=1\"><title>Billing · "
                + escape(settings.applicationName())
                + "</title><style>body{font:17px"
                + " system-ui,sans-serif;line-height:1.5;background:#f5f7fa;color:#152637;margin:0}main{max-width:920px;margin:3rem"
                + " auto;padding:0"
                + " 1.25rem}section,article{background:white;padding:1.25rem;margin:1rem"
                + " 0;border:1px solid"
                + " #d5dfe8;border-radius:.5rem}article{background:#f8fafc}h1,h2,h3{line-height:1.2}a{color:#155c9b}label{display:block;margin:.65rem"
                + " 0}input,select,button{font:inherit;box-sizing:border-box;max-width:100%;padding:.5rem}input{display:block;width:32rem}input[type=hidden]{display:none}button{cursor:pointer;border:1px"
                + " solid #155c9b;border-radius:.3rem;background:#155c9b;color:white;margin:.35rem"
                + " 0}form{margin:.5rem 0}li{margin:.5rem 0}</style></head><body><main>"
                + body
                + "</main></body></html>";
    }

    private static String form(
            String context, String path, CsrfToken csrf, String fields, String label) {
        return "<form method=\"post\" action=\""
                + escape(context + path)
                + "\">"
                + hidden(csrf.getParameterName(), csrf.getToken())
                + fields
                + "<button type=\"submit\">"
                + escape(label)
                + "</button></form>";
    }

    private static String hidden(String name, String value) {
        return "<input type=\"hidden\" name=\""
                + escape(name)
                + "\" value=\""
                + escape(value)
                + "\">";
    }

    private static String input(String name, String label, String value) {
        return "<label>"
                + escape(label)
                + "<input name=\""
                + escape(name)
                + "\" value=\""
                + escape(value)
                + "\" maxlength=\"320\" required></label>";
    }

    private static String link(String target, String label) {
        return "<a href=\""
                + escape(target)
                + "\" rel=\"noreferrer noopener\">"
                + escape(label)
                + "</a>";
    }

    static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String money(MinorUnits value) {
        return BigDecimal.valueOf(value.value(), 2).toPlainString().replace('.', ',') + " €";
    }

    private static String interval(OfferTerms terms) {
        return switch (terms.interval()) {
            case MONTHLY -> "month";
            case YEARLY -> "year";
        };
    }

    private static String benefits(OfferTerms terms) {
        var text = new StringBuilder("<ul>");
        Benefits.entries(terms.benefits())
                .forEach(
                        (key, allowance) ->
                                text.append("<li>")
                                        .append(escape(key.value()))
                                        .append(": ")
                                        .append(
                                                allowance.maximum() == -1
                                                        ? "unbegrenzt"
                                                        : allowance.maximum())
                                        .append("</li>"));
        return text.append("</ul>").toString();
    }

    private static String subscription(SubscriptionStatus status) {
        return switch (status) {
            case ACTIVE -> "Active";
            case AWAITING_PAYMENT -> "Awaiting payment";
            case SUSPENDED -> "Expired";
            case ENDED -> "Ended";
        };
    }

    private static String paymentStatus(PaymentOrderStatus status) {
        return switch (status) {
            case OPEN -> "Processing";
            case PAID -> "Payment confirmed, activation pending";
            case APPLIED -> "Activated";
            case CLOSED -> "Closed";
            case REVIEW -> "Review required";
        };
    }
}
