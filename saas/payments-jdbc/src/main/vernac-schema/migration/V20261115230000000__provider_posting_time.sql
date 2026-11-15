-- Generated migration candidate. Review before applying.
-- You are solely responsible for reviewing, testing, and approving this
-- migration before applying it to any database. Review operation ordering,
-- existing data, application compatibility, locking, execution time,
-- backups and recovery procedures. Generation and automated verification
-- do not guarantee correctness, safety, or suitability for your deployment.

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentAttempt" ADD COLUMN "@nanoRemainder:postedAt" SMALLINT;

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentAttempt" ADD COLUMN "postedAt" TIMESTAMPTZ(6);

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentAttempt" ADD CONSTRAINT "@check:absent:postedAt:org.vernac.saas.payments.PaymentPostedAt" CHECK (("postedAt" IS NOT NULL) OR ("@nanoRemainder:postedAt" IS NULL AND "postedAt" IS NULL));

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentAttempt" ADD CONSTRAINT "@check:range:@nanoRemainder:postedAt" CHECK ("@nanoRemainder:postedAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentAttempt" ADD CONSTRAINT "@check:shape:postedAt" CHECK ((("postedAt" IS NOT NULL) AND ("postedAt" IS NOT NULL AND "@nanoRemainder:postedAt" IS NOT NULL)) OR ((NOT ("postedAt" IS NOT NULL)) AND ("postedAt" IS NULL AND "@nanoRemainder:postedAt" IS NULL)));
