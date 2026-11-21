-- Disposable feasibility fixture, NOT generated production DDL or a Flyway migration.
CREATE TABLE tenant_spike.invoice (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    title TEXT NOT NULL,
    version BIGINT NOT NULL,
    UNIQUE (tenant_id, id)
);
CREATE TABLE tenant_spike.line (
    tenant_id UUID NOT NULL,
    invoice_id UUID NOT NULL,
    position INTEGER NOT NULL,
    PRIMARY KEY (tenant_id, invoice_id, position),
    FOREIGN KEY (tenant_id, invoice_id) REFERENCES tenant_spike.invoice (tenant_id, id)
);
CREATE TABLE tenant_spike.catalog (name TEXT PRIMARY KEY);
INSERT INTO tenant_spike.catalog VALUES ('global reference');
ALTER TABLE tenant_spike.invoice ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_spike.invoice FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON tenant_spike.invoice
    USING (tenant_id = NULLIF(current_setting('vernac.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('vernac.tenant_id', true), '')::uuid);
ALTER TABLE tenant_spike.line ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_spike.line FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON tenant_spike.line
    USING (tenant_id = NULLIF(current_setting('vernac.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('vernac.tenant_id', true), '')::uuid);
