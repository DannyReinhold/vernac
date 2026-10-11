-- Application-owned table, deliberately outside Vernac's owned schema.
-- Review this migration before applying it. Deployment approval remains the operator's responsibility.
CREATE TABLE dispatch_receipt (
    tour_id UUID PRIMARY KEY,
    title TEXT NOT NULL,
    quote_reference TEXT NOT NULL,
    amount NUMERIC NOT NULL,
    currency TEXT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
