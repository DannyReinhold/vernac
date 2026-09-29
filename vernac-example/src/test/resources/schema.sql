CREATE TABLE IF NOT EXISTS orders (
                                      id UUID PRIMARY KEY,
                                      customer_id UUID NOT NULL,
                                      total_amount NUMERIC(19, 4) NOT NULL,
    total_currency VARCHAR(3) NOT NULL,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                             version BIGINT NOT NULL
                             );

CREATE TABLE IF NOT EXISTS order_lines (
                                           id UUID PRIMARY KEY,
                                           order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    sku VARCHAR(100) NOT NULL,
    unit_price_amount NUMERIC(19, 4) NOT NULL,
    unit_price_currency VARCHAR(3) NOT NULL,
    quantity INT NOT NULL
    );