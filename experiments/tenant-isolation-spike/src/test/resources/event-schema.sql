-- Experimental event routing layout. Not generated production schema or a migration.
CREATE TABLE tenant_spike.event_payload (
    id UUID PRIMARY KEY,
    tenant_id UUID,
    scope_key TEXT NOT NULL,
    invoice_id UUID,
    payload TEXT NOT NULL,
    CONSTRAINT event_origin CHECK (scope_key = CASE WHEN tenant_id IS NULL THEN 'GLOBAL' ELSE 'TENANT:' || tenant_id::text END),
    CONSTRAINT event_scope_identity UNIQUE (scope_key, id)
);
CREATE TABLE tenant_spike.event_value (
    tenant_id UUID,
    scope_key TEXT NOT NULL,
    event_id UUID NOT NULL,
    position INTEGER NOT NULL CHECK (position >= 0),
    value TEXT NOT NULL,
    PRIMARY KEY (scope_key, event_id, position),
    CONSTRAINT value_origin CHECK (scope_key = CASE WHEN tenant_id IS NULL THEN 'GLOBAL' ELSE 'TENANT:' || tenant_id::text END),
    FOREIGN KEY (scope_key, event_id) REFERENCES tenant_spike.event_payload(scope_key, id)
);
ALTER TABLE tenant_spike.event_payload ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_spike.event_payload FORCE ROW LEVEL SECURITY;
ALTER TABLE tenant_spike.event_value ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_spike.event_value FORCE ROW LEVEL SECURITY;
CREATE POLICY event_scope ON tenant_spike.event_payload
    USING (tenant_id IS NULL OR tenant_id = NULLIF(current_setting('vernac.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id IS NOT DISTINCT FROM NULLIF(current_setting('vernac.tenant_id',true),'')::uuid);
CREATE POLICY event_scope ON tenant_spike.event_value
    USING (tenant_id IS NULL OR tenant_id = NULLIF(current_setting('vernac.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id IS NOT DISTINCT FROM NULLIF(current_setting('vernac.tenant_id',true),'')::uuid);
-- Control-plane routing only: no business payload, bearer credentials or actor permissions.
CREATE TABLE tenant_spike.event_delivery (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    tenant_id UUID,
    listener TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('PENDING','PROCESSING','SUCCEEDED','FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    token UUID,
    lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    last_error TEXT,
    UNIQUE (event_id, listener),
    CHECK ((status = 'PROCESSING' AND token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'PROCESSING' AND token IS NULL AND lease_until IS NULL))
);
