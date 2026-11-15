---
title: Domestic EUR invoice application
---
# Domestic EUR invoice application

Status: implementation in progress. This does not yet close A10/A11 or the release gate.

```vernac-application
application consumer.invoicing {
    tenancy explicit;
    authorization explicit;
    activate vernac.saas.subscriptions;
    activate vernac.saas.payments.local;
    activate vernac.saas.payments.jdbc;
    activate vernac.saas.invoicing;
    bind org.vernac.saas.payments.PaymentProvider
        to org.vernac.saas.payments.local.LocalPaymentProvider;
    bind org.vernac.saas.invoicing.InvoiceRenderer
        to org.vernac.saas.invoicing.pdf.ZugferdInvoiceRenderer;
}
```

The renderer-only module is selected by its port binding; it has no independent
activatable functions and needs no `activate` statement.

## Selected scope

A German seller supplies domestic German B2B/B2C customers, in EUR. The owner selected
PDF and ZUGFeRD; the renderer produces one readable PDF/A-3b with the same EN16931 XML
embedded as factur-x.xml, and exposes that XML separately. This is not standalone
XRechnung, Peppol routing, EU cross-border, OSS or reverse charge.

The application must explicitly configure the seller's complete name/address, German
tax number or VAT identifier, contact, invoice series and tax treatment. Supported
policies are standard 19 percent and the domestic small-business exemption under
section 19 UStG. Eligibility, turnover thresholds, effective policy changes and the
correct supply classification are the seller's responsibility; no role/payment flag
infers them. There is no tax-provider or jurisdiction guessing fallback.

Relevant primary sources checked on 15 November 2026:

- https://www.gesetze-im-internet.de/ustg_1980/__14.html — invoice contents and advance payments.
- https://www.gesetze-im-internet.de/ustg_1980/__12.html — standard VAT rate.
- https://www.gesetze-im-internet.de/ustg_1980/__19.html — domestic small-business exemption.
- https://www.gesetze-im-internet.de/ustdv_1980/__34a.html — small-business invoice contents.
- https://github.com/ZUGFeRD/mustangproject — pinned Mustangproject 2.26.0 generator and validators.

Passing a format validator does not establish complete legal, tax or archival compliance.
Final fiscal/consumer acceptance remains open in this topic.

## Immutable fiscal facts

InvoiceLedger retrieves a registered verified payment from PaymentFlow; an HTTP body
cannot assert that a payment happened or choose its account. It snapshots buyer,
seller/tax policy, purchased terms, exact service period and provider ledger posting
time. Issue date uses Europe/Berlin independently of machine defaults. Original invoice
and correction snapshots remain unchanged when profiles, offers or configuration change.
A missing complete billing profile stops issuance before allocating a number.

Account-serialized source keys make repeated synchronization idempotent. Independent
series/year counters have transactional locks and unique generated constraints. TEST
and LIVE use separate counters; simulated or real-provider test payments always receive
a TEST-prefixed document and visible test label. No test invoice masquerades as a
production fiscal invoice.

Refund corrections reference the original number/date and use positive credit-note
amounts (supplier correction, not customer self-billing). Cumulative cent attribution
ensures partitions of a full refund reverse the exact original net/tax totals, without
editing an earlier document. Rendering still requires the ordinary arithmetic validator
to accept the resulting EN16931 document; a failure becomes review work.

## Rendering, storage and access

Fiscal numbering and document rendering are separate durable steps. Rendering occurs
outside a short writer; leases fence stale acknowledgements. The PDF/XML insert and
READY state commit atomically. Documents are immutable generated aggregates; bounded
base64 PDF storage uses ordinary generated TEXT persistence rather than an undocumented
binary-store side channel. Maximum PDF size is 4 MiB; XML is limited to 256 KiB. A failed
renderer preserves the fiscal snapshot and payment truth, records REVIEW and supports
an authorized retry after the rendering problem is resolved.

The normal renderer embeds its font, sets an sRGB output intent, first verifies PDF/A-1
through Mustang's strict importer, and validates the final hybrid PDF/A and EN16931 XML.
It does not ignore PDF/A errors or disable arithmetic checks. Optional notices for
other profiles do not constitute claims of XRechnung or Peppol support.

Fresh account administration is mandatory for profile changes, history, retry and
PDF/XML download. Account ownership is checked again when locating the document.
History uses a keyset cursor with at most 100 results. No public controller or open
filesystem URL is installed by this module.

Still open: shared-mail delivery, complete packaged database/concurrency acceptance,
full fresh Identity/HTTP self-service and final fiscal/archival acceptance.
