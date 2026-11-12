-- Generated migration candidate. Review before applying.
-- You are solely responsible for reviewing, testing, and approving this
-- migration before applying it to any database. Review operation ordering,
-- existing data, application compatibility, locking, execution time,
-- backups and recovery procedures. Generation and automated verification
-- do not guarantee correctness, safety, or suitability for your deployment.

ALTER TABLE "showcase.routing"."@event:TourDispatched" ADD COLUMN "@scopeKey" TEXT;

UPDATE "showcase.routing"."@event:TourDispatched" SET "@scopeKey" = ('GLOBAL') WHERE "@scopeKey" IS NULL;

ALTER TABLE "showcase.routing"."@event:TourDispatched" ALTER COLUMN "@scopeKey" SET NOT NULL;

ALTER TABLE "showcase.routing"."@event:TourDispatched" ADD COLUMN "@tenantId" UUID;

ALTER TABLE "showcase.routing"."@event:TourDispatched" ADD CONSTRAINT "@check:event-origin" CHECK ("@scopeKey" = CASE WHEN "@tenantId" IS NULL THEN 'GLOBAL' ELSE 'TENANT:' || "@tenantId"::text END);

ALTER TABLE "showcase.routing"."@event:TourDispatched" ADD CONSTRAINT "@unique:event-origin:@event:TourDispatched" UNIQUE ("@scopeKey", "@eventId");

-- EVENT ORIGIN UPGRADE: reviewed historical rows remain GLOBAL. No tenant attribution is inferred.
-- You alone must verify provenance, deployment ordering and application compatibility before applying.

ALTER TABLE "showcase.routing"."@event:TourDispatched" ENABLE ROW LEVEL SECURITY;

ALTER TABLE "showcase.routing"."@event:TourDispatched" FORCE ROW LEVEL SECURITY;

CREATE POLICY "@event-origin" ON "showcase.routing"."@event:TourDispatched" USING ("@tenantId" IS NULL OR "@tenantId" = NULLIF(current_setting('vernac.tenant_id', true), '')::uuid) WITH CHECK ("@tenantId" IS NOT DISTINCT FROM NULLIF(current_setting('vernac.tenant_id', true), '')::uuid);
