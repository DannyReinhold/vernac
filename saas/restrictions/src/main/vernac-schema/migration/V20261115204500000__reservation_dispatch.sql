-- Generated migration candidate. Review before applying.
-- You are solely responsible for reviewing, testing, and approving this
-- migration before applying it to any database. Review operation ordering,
-- existing data, application compatibility, locking, execution time,
-- backups and recovery procedures. Generation and automated verification
-- do not guarantee correctness, safety, or suitability for your deployment.

ALTER TABLE "org.vernac.saas.restrictions"."QuotaReservation" DROP CONSTRAINT "@check:enum:status";

ALTER TABLE "org.vernac.saas.restrictions"."QuotaReservation" ADD CONSTRAINT "@check:enum:status" CHECK ("status" IN ('CONFIRMED', 'IN_DOUBT', 'RELEASED', 'RESERVED'));
