-- Generated migration candidate. Review before applying.
-- You are solely responsible for reviewing, testing, and approving this
-- migration before applying it to any database. Review operation ordering,
-- existing data, application compatibility, locking, execution time,
-- backups and recovery procedures. Generation and automated verification
-- do not guarantee correctness, safety, or suitability for your deployment.

CREATE TABLE "example.billing"."@event:InvoiceOpened" (
    "@eventId" UUID NOT NULL,
    "@nanoRemainder:@occurredAt" SMALLINT NOT NULL,
    "@occurredAt" TIMESTAMPTZ(6) NOT NULL,
    "customerName.alias" TEXT,
    "customerName.text" TEXT NOT NULL,
    "invoiceId" UUID NOT NULL,
    CONSTRAINT "@pk:@event:InvoiceOpened" PRIMARY KEY ("@eventId")
);

ALTER TABLE "example.billing"."@event:InvoiceOpened" ADD CONSTRAINT "@check:range:@nanoRemainder:@occurredAt" CHECK ("@nanoRemainder:@occurredAt" BETWEEN 0 AND 999);
