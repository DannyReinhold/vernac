-- Generated migration candidate. Review before applying.
-- You are solely responsible for reviewing, testing, and approving this
-- migration before applying it to any database. Review operation ordering,
-- existing data, application compatibility, locking, execution time,
-- backups and recovery procedures. Generation and automated verification
-- do not guarantee correctness, safety, or suitability for your deployment.

ALTER TABLE "org.vernac.saas.identity.persistence"."IdentityChallenge" DROP CONSTRAINT "@check:enum:purpose";

ALTER TABLE "org.vernac.saas.identity.persistence"."IdentityMailJob" DROP CONSTRAINT "@check:enum:purpose";

CREATE TABLE "org.vernac.saas.identity.persistence"."AccountInvitation" (
    "@createdAt" TIMESTAMPTZ(6) NOT NULL,
    "@nanoRemainder:@createdAt" SMALLINT NOT NULL,
    "@nanoRemainder:@updatedAt" SMALLINT NOT NULL,
    "@nanoRemainder:expiresAt" SMALLINT NOT NULL,
    "@updatedAt" TIMESTAMPTZ(6) NOT NULL,
    "@version" BIGINT NOT NULL,
    "account" UUID NOT NULL,
    "delivery" TEXT NOT NULL,
    "expiresAt" TIMESTAMPTZ(6) NOT NULL,
    "id" UUID NOT NULL,
    "issuer" UUID NOT NULL,
    "recipient" TEXT NOT NULL,
    "roles" TEXT NOT NULL,
    "verifier" TEXT NOT NULL,
    CONSTRAINT "@pk:AccountInvitation" PRIMARY KEY ("id")
);

ALTER TABLE "org.vernac.saas.identity.persistence"."AccountInvitation" ADD CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ("@nanoRemainder:@createdAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.identity.persistence"."AccountInvitation" ADD CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ("@nanoRemainder:@updatedAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.identity.persistence"."AccountInvitation" ADD CONSTRAINT "@check:range:@nanoRemainder:expiresAt" CHECK ("@nanoRemainder:expiresAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.identity.persistence"."IdentityChallenge" ADD CONSTRAINT "@check:enum:purpose" CHECK ("purpose" IN ('INVITATION', 'RECOVERY', 'VERIFICATION'));

ALTER TABLE "org.vernac.saas.identity.persistence"."IdentityMailJob" ADD CONSTRAINT "@check:enum:purpose" CHECK ("purpose" IN ('INVITATION', 'RECOVERY', 'VERIFICATION'));

ALTER TABLE "org.vernac.saas.identity.persistence"."AccountInvitation" ADD CONSTRAINT "@fk:reference:account" FOREIGN KEY ("account") REFERENCES "org.vernac.saas.identity.persistence"."Account" ("id");

ALTER TABLE "org.vernac.saas.identity.persistence"."AccountInvitation" ADD CONSTRAINT "@fk:reference:issuer" FOREIGN KEY ("issuer") REFERENCES "org.vernac.saas.identity.persistence"."Identity" ("id");
