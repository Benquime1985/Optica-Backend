-- Conserva los cobros anteriores sin atribuirles un método que no fue registrado.
ALTER TABLE payments ADD COLUMN method VARCHAR(20) NOT NULL DEFAULT 'UNSPECIFIED';
ALTER TABLE payments ADD COLUMN reference VARCHAR(120);
ALTER TABLE payments ADD COLUMN business_date DATE;
UPDATE payments SET business_date=(created_at AT TIME ZONE 'America/Mexico_City')::date;
ALTER TABLE payments ALTER COLUMN business_date SET NOT NULL;
ALTER TABLE payments ADD CONSTRAINT payments_method CHECK(method IN ('CASH','CARD','TRANSFER','UNSPECIFIED'));
ALTER TABLE payments ADD CONSTRAINT payments_id_sale UNIQUE(id,sale_id);

CREATE TABLE refunds (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 payment_id BIGINT NOT NULL, sale_id BIGINT NOT NULL,
 amount NUMERIC(12,2) NOT NULL CHECK(amount>0),
 method VARCHAR(20) NOT NULL CHECK(method IN ('CASH','CARD','TRANSFER','UNSPECIFIED')),
 reference VARCHAR(120), reason VARCHAR(500) NOT NULL,
 received_by BIGINT NOT NULL REFERENCES users(id),
 business_date DATE NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(payment_id,sale_id) REFERENCES payments(id,sale_id)
);
-- Un corte por día para la sucursal. El cierre conserva una fotografía de los importes.
CREATE TABLE cash_days (
 business_date DATE PRIMARY KEY,
 closed_at TIMESTAMPTZ, closed_by BIGINT REFERENCES users(id),
 counted_cash NUMERIC(14,2) CHECK(counted_cash>=0), expected_cash NUMERIC(14,2),
 difference NUMERIC(14,2), notes VARCHAR(1000)
);
CREATE TABLE cash_close_totals (
 business_date DATE NOT NULL REFERENCES cash_days(business_date),
 method VARCHAR(20) NOT NULL,
 collected NUMERIC(14,2) NOT NULL, refunded NUMERIC(14,2) NOT NULL, net NUMERIC(14,2) NOT NULL,
 PRIMARY KEY(business_date,method)
);
ALTER TABLE sales DROP CONSTRAINT sales_status_check;
ALTER TABLE sales ADD CONSTRAINT sales_status_check CHECK(status IN ('OPEN','DELIVERED','CANCELLED','RETURNED'));
ALTER TABLE sales ADD COLUMN closed_at TIMESTAMPTZ;
ALTER TABLE sales ADD COLUMN closed_by BIGINT REFERENCES users(id);
ALTER TABLE sales ADD COLUMN close_reason VARCHAR(500);
ALTER TABLE products ADD COLUMN minimum_stock INTEGER NOT NULL DEFAULT 0 CHECK(minimum_stock>=0);

CREATE TABLE work_orders (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 sale_id BIGINT NOT NULL REFERENCES sales(id),
 prescription_id BIGINT NOT NULL REFERENCES prescriptions(id),
 lens_type VARCHAR(100) NOT NULL, material VARCHAR(100) NOT NULL,
 refractive_index NUMERIC(3,2) CHECK(refractive_index BETWEEN 1 AND 2),
 coating VARCHAR(200), tint VARCHAR(100), laboratory VARCHAR(200) NOT NULL,
 promised_date DATE NOT NULL, notes VARCHAR(2000),
 status VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS'
  CHECK(status IN ('IN_PROGRESS','READY','DELIVERED','CANCELLED','RETURNED')),
 created_by BIGINT NOT NULL REFERENCES users(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE work_order_events (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 work_order_id BIGINT NOT NULL REFERENCES work_orders(id),
 status VARCHAR(20) NOT NULL, changed_by BIGINT NOT NULL REFERENCES users(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX work_orders_sale ON work_orders(sale_id);
CREATE INDEX work_orders_due ON work_orders(status,promised_date);
CREATE INDEX sales_created_seller ON sales(created_at,seller_id);
CREATE INDEX payments_business_date ON payments(business_date);
CREATE INDEX payments_sale ON payments(sale_id);
CREATE INDEX refunds_payment ON refunds(payment_id);
CREATE INDEX refunds_date ON refunds(business_date);
CREATE INDEX sale_items_sale ON sale_items(sale_id);
CREATE INDEX prescriptions_patient ON prescriptions(patient_id);
