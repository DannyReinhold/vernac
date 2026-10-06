CREATE TABLE IF NOT EXISTS purchase_order (
     id UUID PRIMARY KEY,
     customer TEXT NOT NULL,
     total_amount NUMERIC(19, 4) NOT NULL,
     total_currency TEXT NOT NULL,
     status VARCHAR(255) NOT NULL,
     created_at TIMESTAMPTZ NOT NULL,
     updated_at TIMESTAMPTZ NOT NULL,
     version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS order_line (
     id UUID PRIMARY KEY,
     sku VARCHAR(255) NOT NULL,
     unit_price_amount NUMERIC(19, 4) NOT NULL,
     unit_price_currency TEXT NOT NULL,
     quantity INTEGER NOT NULL
);