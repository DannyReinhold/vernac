// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.billing.web;

import org.vernac.saas.identity.domain.AccountId;
import org.vernac.saas.identity.security.IdentityAccountActions;
import org.vernac.saas.invoicing.*;
import org.vernac.saas.invoicing.domain.*;
import org.vernac.saas.payments.domain.*;
import org.vernac.saas.payments.persistence.*;
import org.vernac.saas.payments.persistence.domain.*;
import org.vernac.saas.subscriptions.*;
import org.vernac.saas.subscriptions.domain.*;

import java.util.*;

/**
 * Authorized self-service orchestration. Domain/financial transitions stay in the ordinary
 * application ledgers.
 */
public final class BillingSelfService {
    public record AccountPage(
            AccountId account,
            Optional<SubscriptionLedger.View> subscription,
            List<OfferTerms> offers,
            Optional<InvoiceBuyer> buyer,
            List<PaymentFlow.OrderView> orders,
            List<InvoiceLedger.View> invoices) {
        @Override
        public String toString() {
            return "BillingAccountPage[REDACTED]";
        }
    }

    public record PaymentPage(AccountId account, PaymentFlow.OrderView order) {
        @Override
        public String toString() {
            return "BillingPaymentPage[REDACTED]";
        }
    }

    private final IdentityAccountActions accounts;
    private final BillingAccess access;
    private final PlanCatalog catalog;
    private final SubscriptionLedger subscriptions;
    private final PaymentFlow payments;
    private final InvoiceLedger invoices;
    private final InvoiceMail mail;
    private final BillingSettings settings;

    public BillingSelfService(
            IdentityAccountActions accounts,
            BillingAccess access,
            PlanCatalog catalog,
            SubscriptionLedger subscriptions,
            PaymentFlow payments,
            InvoiceLedger invoices,
            InvoiceMail mail,
            BillingSettings settings) {
        this.accounts = Objects.requireNonNull(accounts);
        this.access = Objects.requireNonNull(access);
        this.catalog = Objects.requireNonNull(catalog);
        this.subscriptions = Objects.requireNonNull(subscriptions);
        this.payments = Objects.requireNonNull(payments);
        this.invoices = Objects.requireNonNull(invoices);
        this.mail = Objects.requireNonNull(mail);
        this.settings = Objects.requireNonNull(settings);
    }

    public List<IdentityAccountActions.AccountView> accounts() {
        return accounts.accounts();
    }

    public AccountPage account(
            AccountId account,
            Optional<PaymentOrderId> afterOrder,
            Optional<InvoiceId> afterInvoice) {
        access.requireOwner(account);
        Optional<SubscriptionLedger.View> current;
        try {
            current = Optional.of(subscriptions.current(account));
        } catch (SubscriptionFailure missing) {
            if (missing.code() != SubscriptionFailure.Code.NOT_FOUND) throw missing;
            current = Optional.empty();
        }
        var offers =
                catalog.offerIds().stream()
                        .sorted(
                                Comparator.comparing((PlanCatalog.OfferId id) -> id.plan().value())
                                        .thenComparing(id -> id.revision().value()))
                        .map(catalog::offer)
                        .toList();
        return new AccountPage(
                account,
                current,
                offers,
                invoices.billingProfile(account),
                payments.orders(account, afterOrder, 20),
                invoices.invoices(account, afterInvoice, 20));
    }

    public void start(AccountId account) {
        access.requireOwner(account);
        subscriptions.ensureFree(account);
    }

    public void profile(AccountId account, InvoiceBuyer buyer) {
        access.requireOwner(account);
        invoices.billingProfile(account, buyer);
    }

    public PaymentFlow.OrderView checkout(
            AccountId account,
            PaymentOperationId request,
            PaymentProfileKey profile,
            PlanCatalog.OfferId offer) {
        access.requireOwner(account);
        if (invoices.billingProfile(account).isEmpty())
            throw new InvoiceFailure(InvoiceFailure.Code.BILLING_PROFILE_REQUIRED);
        if (settings.payments().stream().noneMatch(choice -> choice.profile().equals(profile)))
            throw new PaymentProcessingFailure(PaymentProcessingFailure.Code.CONFIGURATION);
        return payments.checkout(account, request, profile, offer);
    }

    public PaymentPage payment(AccountId account, PaymentOrderId order) {
        access.requireOwner(account);
        return new PaymentPage(account, payments.order(account, order));
    }

    public void subscription(
            AccountId account, String action, Optional<PlanCatalog.OfferId> offer) {
        access.requireOwner(account);
        switch (action) {
            case "schedule" ->
                    subscriptions.schedule(
                            account,
                            offer.orElseThrow(
                                    () -> new IllegalArgumentException("Offer required")));
            case "cancel" -> subscriptions.cancel(account);
            case "resume" -> subscriptions.resume(account);
            case "reopen" -> subscriptions.reopenFree(account);
            default -> throw new IllegalArgumentException("Invalid subscription action");
        }
    }

    public void retryPayment(AccountId account, PaymentOrderId order) {
        access.requireOwner(account);
        payments.retry(account, order);
    }

    public void closeUnpaid(AccountId account, PaymentOrderId order) {
        access.requireOwner(account);
        payments.closeUnpaid(account, order);
    }

    public void reconcile(AccountId account, PaymentOrderId order, RemotePaymentId remote) {
        access.requireOwner(account);
        payments.reconcilePayment(account, order, remote);
    }

    public void retryInvoice(AccountId account, InvoiceId invoice) {
        access.requireOwner(account);
        invoices.retryRendering(account, invoice);
    }

    public void retryMail(AccountId account, InvoiceId invoice) {
        access.requireOwner(account);
        mail.retry(account, invoice);
    }

    public InvoiceLedger.View invoice(InvoiceId invoice) {
        return invoices.invoice(invoice);
    }

    public RenderedInvoice download(InvoiceId invoice) {
        return invoices.download(invoice);
    }

    public Optional<InvoiceMail.View> mail(AccountId account, InvoiceId invoice) {
        access.requireOwner(account);
        return mail.status(account, invoice);
    }
}
