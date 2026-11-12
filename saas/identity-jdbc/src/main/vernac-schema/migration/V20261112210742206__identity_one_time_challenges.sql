-- Generated migration candidate. Review before applying.
-- You are solely responsible for reviewing, testing, and approving this
-- migration before applying it to any database. Review operation ordering,
-- existing data, application compatibility, locking, execution time,
-- backups and recovery procedures. Generation and automated verification
-- do not guarantee correctness, safety, or suitability for your deployment.

CREATE TABLE "org.vernac.saas.identity.persistence"."IdentityChallenge" (
    "@createdAt" TIMESTAMPTZ(6) NOT NULL,
    "@nanoRemainder:@createdAt" SMALLINT NOT NULL,
    "@nanoRemainder:@updatedAt" SMALLINT NOT NULL,
    "@nanoRemainder:expiresAt" SMALLINT NOT NULL,
    "@updatedAt" TIMESTAMPTZ(6) NOT NULL,
    "@version" BIGINT NOT NULL,
    "expiresAt" TIMESTAMPTZ(6) NOT NULL,
    "id" UUID NOT NULL,
    "purpose" TEXT NOT NULL,
    "subject" UUID NOT NULL,
    "verifier" TEXT NOT NULL,
    CONSTRAINT "@pk:IdentityChallenge" PRIMARY KEY ("id")
);

ALTER TABLE "org.vernac.saas.identity.persistence"."IdentityChallenge" ADD CONSTRAINT "@check:enum:purpose" CHECK ("purpose" IN ('RECOVERY', 'VERIFICATION'));

ALTER TABLE "org.vernac.saas.identity.persistence"."IdentityChallenge" ADD CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ("@nanoRemainder:@createdAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.identity.persistence"."IdentityChallenge" ADD CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ("@nanoRemainder:@updatedAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.identity.persistence"."IdentityChallenge" ADD CONSTRAINT "@check:range:@nanoRemainder:expiresAt" CHECK ("@nanoRemainder:expiresAt" BETWEEN 0 AND 999);
