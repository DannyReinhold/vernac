-- Generated migration candidate. Review before applying.
-- You are solely responsible for reviewing, testing, and approving this
-- migration before applying it to any database. Review operation ordering,
-- existing data, application compatibility, locking, execution time,
-- backups and recovery procedures. Generation and automated verification
-- do not guarantee correctness, safety, or suitability for your deployment.

CREATE SCHEMA IF NOT EXISTS "org.example.tasks";

CREATE TABLE "org.example.tasks"."Task" (
    "@createdAt" TIMESTAMPTZ(6) NOT NULL,
    "@nanoRemainder:@createdAt" SMALLINT NOT NULL,
    "@nanoRemainder:@updatedAt" SMALLINT NOT NULL,
    "@updatedAt" TIMESTAMPTZ(6) NOT NULL,
    "@version" BIGINT NOT NULL,
    "id" UUID NOT NULL,
    "taskStatus" TEXT NOT NULL,
    "taskTitle" TEXT NOT NULL,
    CONSTRAINT "@pk:Task" PRIMARY KEY ("id")
);

ALTER TABLE "org.example.tasks"."Task" ADD CONSTRAINT "@check:enum:taskStatus" CHECK ("taskStatus" IN ('DONE', 'OPEN'));

ALTER TABLE "org.example.tasks"."Task" ADD CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ("@nanoRemainder:@createdAt" BETWEEN 0 AND 999);

ALTER TABLE "org.example.tasks"."Task" ADD CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ("@nanoRemainder:@updatedAt" BETWEEN 0 AND 999);
