-- El fondo de apertura se suma al efectivo neto al calcular el dinero esperado en caja.
ALTER TABLE cash_days ADD COLUMN opening_cash NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK(opening_cash>=0);
ALTER TABLE cash_days ADD COLUMN opened_at TIMESTAMPTZ;
ALTER TABLE cash_days ADD COLUMN opened_by BIGINT REFERENCES users(id);
