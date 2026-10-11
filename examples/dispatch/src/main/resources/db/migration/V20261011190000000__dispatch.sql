-- Generated migration candidate. Review before applying.
-- You are solely responsible for reviewing, testing, and approving this
-- migration before applying it to any database. Review operation ordering,
-- existing data, application compatibility, locking, execution time,
-- backups and recovery procedures. Generation and automated verification
-- do not guarantee correctness, safety, or suitability for your deployment.

CREATE SCHEMA IF NOT EXISTS "showcase.routing";

CREATE SCHEMA IF NOT EXISTS "vernac";

CREATE TABLE "showcase.routing"."@event:TourDispatched" (
    "@eventId" UUID NOT NULL,
    "@nanoRemainder:@occurredAt" SMALLINT NOT NULL,
    "@occurredAt" TIMESTAMPTZ(6) NOT NULL,
    "@scale:shipment.weight" INTEGER NOT NULL,
    "shipment.destination" TEXT NOT NULL,
    "shipment.weight" NUMERIC NOT NULL,
    "title" TEXT NOT NULL,
    "tourId" UUID NOT NULL,
    CONSTRAINT "@pk:@event:TourDispatched" PRIMARY KEY ("@eventId")
);

CREATE TABLE "showcase.routing"."Tour" (
    "@createdAt" TIMESTAMPTZ(6) NOT NULL,
    "@nanoRemainder:@createdAt" SMALLINT NOT NULL,
    "@nanoRemainder:@updatedAt" SMALLINT NOT NULL,
    "@updatedAt" TIMESTAMPTZ(6) NOT NULL,
    "@version" BIGINT NOT NULL,
    "capacity" INTEGER NOT NULL,
    "id" UUID NOT NULL,
    "title" TEXT NOT NULL,
    "tourState" TEXT NOT NULL,
    CONSTRAINT "@pk:Tour" PRIMARY KEY ("id")
);

CREATE TABLE "showcase.routing"."Tour.@entity:showcase.routing.Stop" (
    "@aggregateId" UUID NOT NULL,
    "address.city" TEXT NOT NULL,
    "address.street" TEXT NOT NULL,
    "customer" TEXT NOT NULL,
    "id" UUID NOT NULL,
    "units" INTEGER NOT NULL,
    CONSTRAINT "@pk:Tour.@entity:showcase.routing.Stop" PRIMARY KEY ("@aggregateId", "id")
);

CREATE TABLE "showcase.routing"."Tour.stops" (
    "@aggregateId" UUID NOT NULL,
    "@entityId" UUID NOT NULL,
    "@position" INTEGER NOT NULL,
    CONSTRAINT "@pk:Tour.stops" PRIMARY KEY ("@aggregateId", "@position")
);

CREATE TABLE "vernac"."@delivery" (
    "attempts" INTEGER NOT NULL,
    "claimToken" UUID,
    "completedAt" TIMESTAMPTZ(6),
    "createdAt" TIMESTAMPTZ(6) NOT NULL,
    "eventId" UUID NOT NULL,
    "eventType" TEXT NOT NULL,
    "id" UUID NOT NULL,
    "lastAttemptAt" TIMESTAMPTZ(6),
    "lastError" TEXT,
    "leaseUntil" TIMESTAMPTZ(6),
    "listenerId" TEXT NOT NULL,
    "nextAttemptAt" TIMESTAMPTZ(6) NOT NULL,
    "status" TEXT NOT NULL,
    CONSTRAINT "@pk:@delivery" PRIMARY KEY ("id")
);

ALTER TABLE "showcase.routing"."@event:TourDispatched" ADD CONSTRAINT "@check:range:@nanoRemainder:@occurredAt" CHECK ("@nanoRemainder:@occurredAt" BETWEEN 0 AND 999);

ALTER TABLE "showcase.routing"."@event:TourDispatched" ADD CONSTRAINT "@check:range:shipment.weight" CHECK ("shipment.weight" NOT IN ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric));

ALTER TABLE "showcase.routing"."Tour" ADD CONSTRAINT "@check:enum:tourState" CHECK ("tourState" IN ('DISPATCHED', 'PLANNED', 'READY'));

ALTER TABLE "showcase.routing"."Tour" ADD CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ("@nanoRemainder:@createdAt" BETWEEN 0 AND 999);

ALTER TABLE "showcase.routing"."Tour" ADD CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ("@nanoRemainder:@updatedAt" BETWEEN 0 AND 999);

ALTER TABLE "showcase.routing"."Tour.stops" ADD CONSTRAINT "@check:position" CHECK ("@position" >= 0);

ALTER TABLE "vernac"."@delivery" ADD CONSTRAINT "@check:attempts" CHECK ("attempts" >= 0);

ALTER TABLE "vernac"."@delivery" ADD CONSTRAINT "@check:claim" CHECK (("status" = 'PROCESSING' AND "claimToken" IS NOT NULL AND "leaseUntil" IS NOT NULL) OR ("status" <> 'PROCESSING' AND "claimToken" IS NULL AND "leaseUntil" IS NULL));

ALTER TABLE "vernac"."@delivery" ADD CONSTRAINT "@check:status" CHECK ("status" IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'BLOCKED'));

ALTER TABLE "vernac"."@delivery" ADD CONSTRAINT "@unique:delivery" UNIQUE ("eventType", "eventId", "listenerId");

ALTER TABLE "showcase.routing"."Tour.@entity:showcase.routing.Stop" ADD CONSTRAINT "@fk:aggregate" FOREIGN KEY ("@aggregateId") REFERENCES "showcase.routing"."Tour" ("id");

ALTER TABLE "showcase.routing"."Tour.stops" ADD CONSTRAINT "@fk:entity" FOREIGN KEY ("@aggregateId", "@entityId") REFERENCES "showcase.routing"."Tour.@entity:showcase.routing.Stop" ("@aggregateId", "id") DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE "showcase.routing"."Tour.stops" ADD CONSTRAINT "@fk:owner" FOREIGN KEY ("@aggregateId") REFERENCES "showcase.routing"."Tour" ("id");
