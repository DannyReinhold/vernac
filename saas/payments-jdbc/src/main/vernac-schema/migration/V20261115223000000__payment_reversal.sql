-- Generated migration candidate. Review before applying.
-- You are solely responsible for reviewing, testing, and approving this
-- migration before applying it to any database. Review operation ordering,
-- existing data, application compatibility, locking, execution time,
-- backups and recovery procedures. Generation and automated verification
-- do not guarantee correctness, safety, or suitability for your deployment.

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentAttempt" DROP CONSTRAINT "@check:enum:stage";

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentAttempt" ADD CONSTRAINT "@check:enum:stage" CHECK ("stage" IN ('APPLY', 'CUSTOMER_READY', 'DONE', 'NEW', 'PREPARING', 'REVIEW', 'REVOKE', 'WAITING'));
