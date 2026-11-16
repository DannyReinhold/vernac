// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.billing.lifecycle;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.vernac.saas.invoicing.*;
import org.vernac.saas.payments.domain.PaymentUri;
import org.vernac.saas.payments.persistence.PaymentFlow;
import org.vernac.saas.payments.persistence.domain.PaymentOrderId;

/** Explicit bounded worker tick. A failed stage cannot starve unrelated rendering/mail/recovery.
 * Counters are process-local liveness, not durable queue counts or proof of external delivery.
 */
public final class BillingMaintenance {
    public enum Stage { RENEWAL, PAYMENT, REFUND, INVOICE_SYNC, RENDER, MAIL }
    public record Progress(long completed, long failed) {}
    private static final class Counts { final AtomicLong completed=new AtomicLong(),failed=new AtomicLong(); }
    private final Map<Stage,Counts> counts=new EnumMap<>(Stage.class);
    private final PaymentFlow payments;
    private final InvoiceLedger invoices;
    private final InvoiceMail mail;
    private final PaymentUri returnTo,webhook;
    private Optional<PaymentOrderId> after=Optional.empty();
    public BillingMaintenance(PaymentFlow payments, InvoiceLedger invoices, InvoiceMail mail, PaymentUri returnTo, PaymentUri webhook) {
        this.payments=Objects.requireNonNull(payments);this.invoices=Objects.requireNonNull(invoices);this.mail=Objects.requireNonNull(mail);
        this.returnTo=Objects.requireNonNull(returnTo);this.webhook=Objects.requireNonNull(webhook);
        for(var stage:Stage.values())counts.put(stage,new Counts());
    }
    public synchronized void tick() {
        run(Stage.RENEWAL,()->payments.scheduleRenewalOne());
        run(Stage.PAYMENT,()->payments.processOne(returnTo,webhook));
        run(Stage.REFUND,()->payments.processRefundOne());
        run(Stage.INVOICE_SYNC,()-> {
            var rows=payments.verifiedPayments(after,10);
            for(var row:rows) {
                // Advance even after one bad account/profile; every complete sweep revisits it.
                run(Stage.INVOICE_SYNC,()->invoices.synchronize(row.order()));after=Optional.of(row.order());
            }
            if(rows.size()<10)after=Optional.empty();
        });
        run(Stage.RENDER,()->invoices.renderOne());
        run(Stage.MAIL,()->mail.processOne());
    }
    private void run(Stage stage,Runnable action) {
        try {action.run();counts.get(stage).completed.incrementAndGet();}
        catch(RuntimeException failure) {counts.get(stage).failed.incrementAndGet();}
    }
    public Map<Stage,Progress> progress() {
        var snapshot=new EnumMap<Stage,Progress>(Stage.class);
        counts.forEach((stage,value)->snapshot.put(stage,new Progress(value.completed.get(),value.failed.get())));
        return Collections.unmodifiableMap(snapshot);
    }
}
