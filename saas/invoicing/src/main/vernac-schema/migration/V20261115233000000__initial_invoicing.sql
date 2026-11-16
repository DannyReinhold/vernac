-- Generated migration candidate. Review before applying.
-- You are solely responsible for reviewing, testing, and approving this
-- migration before applying it to any database. Review operation ordering,
-- existing data, application compatibility, locking, execution time,
-- backups and recovery procedures. Generation and automated verification
-- do not guarantee correctness, safety, or suitability for your deployment.

CREATE SCHEMA IF NOT EXISTS "org.vernac.saas.invoicing";

CREATE TABLE "org.vernac.saas.invoicing"."BillingProfile" (
    "@createdAt" TIMESTAMPTZ(6) NOT NULL,
    "@nanoRemainder:@createdAt" SMALLINT NOT NULL,
    "@nanoRemainder:@updatedAt" SMALLINT NOT NULL,
    "@updatedAt" TIMESTAMPTZ(6) NOT NULL,
    "@version" BIGINT NOT NULL,
    "buyer.contact" TEXT NOT NULL,
    "buyer.customer" TEXT NOT NULL,
    "buyer.party.city" TEXT NOT NULL,
    "buyer.party.name" TEXT NOT NULL,
    "buyer.party.postalCode" TEXT NOT NULL,
    "buyer.party.street" TEXT NOT NULL,
    "id" UUID NOT NULL,
    CONSTRAINT "@pk:BillingProfile" PRIMARY KEY ("id")
);

CREATE TABLE "org.vernac.saas.invoicing"."Invoice" (
    "@createdAt" TIMESTAMPTZ(6) NOT NULL,
    "@nanoRemainder:@createdAt" SMALLINT NOT NULL,
    "@nanoRemainder:@updatedAt" SMALLINT NOT NULL,
    "@nanoRemainder:content.paymentPostedAt" SMALLINT NOT NULL,
    "@nanoRemainder:content.period.end" SMALLINT NOT NULL,
    "@nanoRemainder:content.period.start" SMALLINT NOT NULL,
    "@nanoRemainder:due" SMALLINT NOT NULL,
    "@nanoRemainder:lease.until" SMALLINT,
    "@updatedAt" TIMESTAMPTZ(6) NOT NULL,
    "@version" BIGINT NOT NULL,
    "account" UUID NOT NULL,
    "content.amounts.gross" BIGINT NOT NULL,
    "content.amounts.net" BIGINT NOT NULL,
    "content.amounts.tax" BIGINT NOT NULL,
    "content.buyer.contact" TEXT NOT NULL,
    "content.buyer.customer" TEXT NOT NULL,
    "content.buyer.party.city" TEXT NOT NULL,
    "content.buyer.party.name" TEXT NOT NULL,
    "content.buyer.party.postalCode" TEXT NOT NULL,
    "content.buyer.party.street" TEXT NOT NULL,
    "content.description" TEXT NOT NULL,
    "content.environment" TEXT NOT NULL,
    "content.issued" DATE NOT NULL,
    "content.kind" TEXT NOT NULL,
    "content.number" TEXT NOT NULL,
    "content.original.issued" DATE,
    "content.original.number" TEXT,
    "content.paymentPostedAt" TIMESTAMPTZ(6) NOT NULL,
    "content.period.end" TIMESTAMPTZ(6) NOT NULL,
    "content.period.start" TIMESTAMPTZ(6) NOT NULL,
    "content.seller.contact" TEXT NOT NULL,
    "content.seller.party.city" TEXT NOT NULL,
    "content.seller.party.name" TEXT NOT NULL,
    "content.seller.party.postalCode" TEXT NOT NULL,
    "content.seller.party.street" TEXT NOT NULL,
    "content.seller.taxIdentifier" TEXT NOT NULL,
    "content.seller.treatment" TEXT NOT NULL,
    "content.terms.benefits" TEXT NOT NULL,
    "content.terms.currency" TEXT NOT NULL,
    "content.terms.interval" TEXT NOT NULL,
    "content.terms.plan" TEXT NOT NULL,
    "content.terms.price" BIGINT NOT NULL,
    "content.terms.revision" TEXT NOT NULL,
    "cumulativeRefund" BIGINT NOT NULL,
    "due" TIMESTAMPTZ(6) NOT NULL,
    "id" UUID NOT NULL,
    "lease.token" UUID,
    "lease.until" TIMESTAMPTZ(6),
    "number" TEXT COLLATE "pg_catalog"."C" NOT NULL,
    "payment" UUID NOT NULL,
    "source" TEXT COLLATE "pg_catalog"."C" NOT NULL,
    "status" TEXT NOT NULL,
    CONSTRAINT "@pk:Invoice" PRIMARY KEY ("id")
);

CREATE TABLE "org.vernac.saas.invoicing"."InvoiceSequence" (
    "@createdAt" TIMESTAMPTZ(6) NOT NULL,
    "@nanoRemainder:@createdAt" SMALLINT NOT NULL,
    "@nanoRemainder:@updatedAt" SMALLINT NOT NULL,
    "@updatedAt" TIMESTAMPTZ(6) NOT NULL,
    "@version" BIGINT NOT NULL,
    "id" UUID NOT NULL,
    "key" TEXT COLLATE "pg_catalog"."C" NOT NULL,
    "last" BIGINT NOT NULL,
    CONSTRAINT "@pk:InvoiceSequence" PRIMARY KEY ("id")
);

CREATE TABLE "org.vernac.saas.invoicing"."IssuedInvoiceDocument" (
    "@createdAt" TIMESTAMPTZ(6) NOT NULL,
    "@nanoRemainder:@createdAt" SMALLINT NOT NULL,
    "@nanoRemainder:@updatedAt" SMALLINT NOT NULL,
    "@updatedAt" TIMESTAMPTZ(6) NOT NULL,
    "@version" BIGINT NOT NULL,
    "document.pdf" TEXT NOT NULL,
    "document.xml" TEXT NOT NULL,
    "id" UUID NOT NULL,
    "invoice" UUID NOT NULL,
    CONSTRAINT "@pk:IssuedInvoiceDocument" PRIMARY KEY ("id")
);

ALTER TABLE "org.vernac.saas.invoicing"."BillingProfile" ADD CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ("@nanoRemainder:@createdAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.invoicing"."BillingProfile" ADD CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ("@nanoRemainder:@updatedAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.invoicing"."Invoice" ADD CONSTRAINT "@check:absent:content.original~a72167f02e16cea6f886b19423239207" CHECK (("content.original.number" IS NOT NULL) OR ("content.original.issued" IS NULL));

ALTER TABLE "org.vernac.saas.invoicing"."Invoice" ADD CONSTRAINT "@check:absent:content.original~8bd45770db5f00de9089f0cbcc499e0d" CHECK (("content.original.number" IS NOT NULL) OR ("content.original.issued" IS NULL AND "content.original.number" IS NULL));

ALTER TABLE "org.vernac.saas.invoicing"."Invoice" ADD CONSTRAINT "@check:absent:lease:org.vernac.saas.invoicing.InvoiceWorkLease" CHECK (("lease.token" IS NOT NULL) OR ("@nanoRemainder:lease.until" IS NULL AND "lease.token" IS NULL AND "lease.until" IS NULL));

ALTER TABLE "org.vernac.saas.invoicing"."Invoice" ADD CONSTRAINT "@check:enum:content.environment" CHECK ("content.environment" IN ('LIVE', 'TEST'));

ALTER TABLE "org.vernac.saas.invoicing"."Invoice" ADD CONSTRAINT "@check:enum:content.kind" CHECK ("content.kind" IN ('CORRECTION', 'INVOICE'));

ALTER TABLE "org.vernac.saas.invoicing"."Invoice" ADD CONSTRAINT "@check:enum:content.seller.treatment" CHECK ("content.seller.treatment" IN ('SMALL_BUSINESS_EXEMPT', 'STANDARD_19'));

ALTER TABLE "org.vernac.saas.invoicing"."Invoice" ADD CONSTRAINT "@check:enum:content.terms.currency" CHECK ("content.terms.currency" IN ('EUR'));

ALTER TABLE "org.vernac.saas.invoicing"."Invoice" ADD CONSTRAINT "@check:enum:content.terms.interval" CHECK ("content.terms.interval" IN ('MONTHLY', 'YEARLY'));

ALTER TABLE "org.vernac.saas.invoicing"."Invoice" ADD CONSTRAINT "@check:enum:status" CHECK ("status" IN ('PENDING', 'READY', 'REVIEW'));

ALTER TABLE "org.vernac.saas.invoicing"."Invoice" ADD CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ("@nanoRemainder:@createdAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.invoicing"."Invoice" ADD CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ("@nanoRemainder:@updatedAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.invoicing"."Invoice" ADD CONSTRAINT "@check:range:@nanoRemainder:content.paymentPostedAt" CHECK ("@nanoRemainder:content.paymentPostedAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.invoicing"."Invoice" ADD CONSTRAINT "@check:range:@nanoRemainder:content.period.end" CHECK ("@nanoRemainder:content.period.end" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.invoicing"."Invoice" ADD CONSTRAINT "@check:range:@nanoRemainder:content.period.start" CHECK ("@nanoRemainder:content.period.start" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.invoicing"."Invoice" ADD CONSTRAINT "@check:range:@nanoRemainder:due" CHECK ("@nanoRemainder:due" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.invoicing"."Invoice" ADD CONSTRAINT "@check:range:@nanoRemainder:lease.until" CHECK ("@nanoRemainder:lease.until" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.invoicing"."Invoice" ADD CONSTRAINT "@check:shape:content.original.issued" CHECK ((("content.original.number" IS NOT NULL) AND ("content.original.issued" IS NOT NULL)) OR ((NOT ("content.original.number" IS NOT NULL)) AND ("content.original.issued" IS NULL)));

ALTER TABLE "org.vernac.saas.invoicing"."Invoice" ADD CONSTRAINT "@check:shape:lease.until" CHECK ((("lease.token" IS NOT NULL) AND ("lease.until" IS NOT NULL AND "@nanoRemainder:lease.until" IS NOT NULL)) OR ((NOT ("lease.token" IS NOT NULL)) AND ("lease.until" IS NULL AND "@nanoRemainder:lease.until" IS NULL)));

ALTER TABLE "org.vernac.saas.invoicing"."Invoice" ADD CONSTRAINT "@unique:Invoice:number" UNIQUE ("number");

ALTER TABLE "org.vernac.saas.invoicing"."Invoice" ADD CONSTRAINT "@unique:Invoice:source" UNIQUE ("source");

ALTER TABLE "org.vernac.saas.invoicing"."InvoiceSequence" ADD CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ("@nanoRemainder:@createdAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.invoicing"."InvoiceSequence" ADD CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ("@nanoRemainder:@updatedAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.invoicing"."InvoiceSequence" ADD CONSTRAINT "@unique:InvoiceSequence:key" UNIQUE ("key");

ALTER TABLE "org.vernac.saas.invoicing"."IssuedInvoiceDocument" ADD CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ("@nanoRemainder:@createdAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.invoicing"."IssuedInvoiceDocument" ADD CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ("@nanoRemainder:@updatedAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.invoicing"."IssuedInvoiceDocument" ADD CONSTRAINT "@unique:IssuedInvoiceDocument:invoice" UNIQUE ("invoice");

ALTER TABLE "org.vernac.saas.invoicing"."IssuedInvoiceDocument" ADD CONSTRAINT "@fk:reference:invoice" FOREIGN KEY ("invoice") REFERENCES "org.vernac.saas.invoicing"."Invoice" ("id");
