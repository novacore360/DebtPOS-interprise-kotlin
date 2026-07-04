-- ============================================================================
-- Marnie Store POS — Neon Postgres schema
-- ============================================================================
-- Run with: psql "$NEON_DATABASE_URL" -f sql/schema.sql
--
-- Design notes:
--  * This app is single-tenant / single-admin by design: there is exactly
--    one operator, authenticated with credentials from environment
--    variables (see docs/DEPLOY.md) rather than a `users` table. There is
--    no `stores` table and no per-row tenant scoping — every row implicitly
--    belongs to the one store this backend serves.
--  * Every syncable table has: id (uuid), updated_at, is_deleted (soft
--    delete so offline clients can reconcile tombstones), version (bigint,
--    bumped on every write — used for optimistic concurrency and as the
--    sync cursor together with updated_at).
--  * A NOTIFY trigger fires on every insert/update/delete so the backend can
--    push realtime events to connected devices over WebSocket.
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid()

CREATE TABLE IF NOT EXISTS refresh_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    token_hash      TEXT NOT NULL,                -- sha256 of the raw refresh token
    device_id       TEXT NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_device ON refresh_tokens(device_id);

-- ---------------------------------------------------------------------------
-- Products
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS products (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_code        TEXT,                      -- barcode
    name                TEXT NOT NULL,
    category            TEXT,
    cost_price          NUMERIC(12,2) NOT NULL DEFAULT 0,
    retail_price        NUMERIC(12,2) NOT NULL DEFAULT 0,
    price               NUMERIC(12,2) NOT NULL DEFAULT 0,
    stock               INT NOT NULL DEFAULT 0,
    low_stock_threshold INT NOT NULL DEFAULT 5,
    is_deleted          BOOLEAN NOT NULL DEFAULT FALSE,
    version             BIGINT NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_products_updated ON products(updated_at);
CREATE INDEX IF NOT EXISTS idx_products_code ON products(product_code);

-- ---------------------------------------------------------------------------
-- Customers
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS customers (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            TEXT NOT NULL,
    phone           TEXT,
    email           TEXT,
    access_pin_hash TEXT,                          -- hashed 5-digit customer portal PIN
    is_deleted      BOOLEAN NOT NULL DEFAULT FALSE,
    version         BIGINT NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_customers_updated ON customers(updated_at);

-- ---------------------------------------------------------------------------
-- Purchases (a purchase = a debt/sale ticket) + line items (normalized,
-- replacing the JSON-string product_data blob from the old Firestore model)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS purchases (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id     UUID REFERENCES customers(id) ON DELETE SET NULL,
    customer_name   TEXT,                          -- denormalized snapshot at sale time
    total_amount    NUMERIC(12,2) NOT NULL DEFAULT 0,
    amount_paid     NUMERIC(12,2) NOT NULL DEFAULT 0,
    status          TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','paid','partial','void')),
    purchase_date   TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_deleted      BOOLEAN NOT NULL DEFAULT FALSE,
    version         BIGINT NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_purchases_updated ON purchases(updated_at);
CREATE INDEX IF NOT EXISTS idx_purchases_customer ON purchases(customer_id);
CREATE INDEX IF NOT EXISTS idx_purchases_date ON purchases(purchase_date DESC);

CREATE TABLE IF NOT EXISTS purchase_items (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    purchase_id     UUID NOT NULL REFERENCES purchases(id) ON DELETE CASCADE,
    product_id      UUID REFERENCES products(id) ON DELETE SET NULL,
    name            TEXT NOT NULL,                 -- snapshot of product name at sale time
    price           NUMERIC(12,2) NOT NULL DEFAULT 0,
    quantity        INT NOT NULL DEFAULT 1,
    subtotal        NUMERIC(12,2) NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_purchase_items_purchase ON purchase_items(purchase_id);

-- Payments — supports partial debt repayment history (essential feature
-- missing in the old app: previously "paid" was a single boolean flip)
CREATE TABLE IF NOT EXISTS payments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    purchase_id     UUID NOT NULL REFERENCES purchases(id) ON DELETE CASCADE,
    customer_id     UUID REFERENCES customers(id) ON DELETE SET NULL,
    amount          NUMERIC(12,2) NOT NULL,
    method          TEXT NOT NULL DEFAULT 'cash',
    note            TEXT,
    is_deleted      BOOLEAN NOT NULL DEFAULT FALSE,
    version         BIGINT NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_payments_updated ON payments(updated_at);

-- ---------------------------------------------------------------------------
-- Audit log — every mutating action, for security/traceability. There's
-- only ever one operator, but this still records what/when/from where.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS audit_log (
    id              BIGSERIAL PRIMARY KEY,
    action          TEXT NOT NULL,
    entity_type     TEXT NOT NULL,
    entity_id       UUID,
    metadata        JSONB,
    ip_address      TEXT,
    device_id       TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_audit_time ON audit_log(created_at DESC);

-- ============================================================================
-- updated_at / version bump trigger (applies to every syncable table)
-- ============================================================================
CREATE OR REPLACE FUNCTION touch_row() RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at := now();
    NEW.version := COALESCE(OLD.version, 0) + 1;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_products_touch ON products;
CREATE TRIGGER trg_products_touch BEFORE UPDATE ON products
    FOR EACH ROW EXECUTE FUNCTION touch_row();

DROP TRIGGER IF EXISTS trg_customers_touch ON customers;
CREATE TRIGGER trg_customers_touch BEFORE UPDATE ON customers
    FOR EACH ROW EXECUTE FUNCTION touch_row();

DROP TRIGGER IF EXISTS trg_purchases_touch ON purchases;
CREATE TRIGGER trg_purchases_touch BEFORE UPDATE ON purchases
    FOR EACH ROW EXECUTE FUNCTION touch_row();

DROP TRIGGER IF EXISTS trg_payments_touch ON payments;
CREATE TRIGGER trg_payments_touch BEFORE UPDATE ON payments
    FOR EACH ROW EXECUTE FUNCTION touch_row();

-- ============================================================================
-- Realtime NOTIFY — backend LISTENs on 'pos_changes' and relays to every
-- WebSocket-connected device (single store, so no per-tenant fan-out).
-- ============================================================================
CREATE OR REPLACE FUNCTION notify_change() RETURNS TRIGGER AS $$
DECLARE
    payload JSONB;
    row_data JSONB;
BEGIN
    row_data := to_jsonb(COALESCE(NEW, OLD));
    payload := jsonb_build_object(
        'table', TG_TABLE_NAME,
        'op', TG_OP,
        'id', row_data->>'id',
        'updated_at', row_data->>'updated_at'
    );
    PERFORM pg_notify('pos_changes', payload::text);
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_products_notify ON products;
CREATE TRIGGER trg_products_notify AFTER INSERT OR UPDATE OR DELETE ON products
    FOR EACH ROW EXECUTE FUNCTION notify_change();

DROP TRIGGER IF EXISTS trg_customers_notify ON customers;
CREATE TRIGGER trg_customers_notify AFTER INSERT OR UPDATE OR DELETE ON customers
    FOR EACH ROW EXECUTE FUNCTION notify_change();

DROP TRIGGER IF EXISTS trg_purchases_notify ON purchases;
CREATE TRIGGER trg_purchases_notify AFTER INSERT OR UPDATE OR DELETE ON purchases
    FOR EACH ROW EXECUTE FUNCTION notify_change();

DROP TRIGGER IF EXISTS trg_payments_notify ON payments;
CREATE TRIGGER trg_payments_notify AFTER INSERT OR UPDATE OR DELETE ON payments
    FOR EACH ROW EXECUTE FUNCTION notify_change();

-- ============================================================================
-- Migrating from an older multi-user/multi-store copy of this schema?
-- Run this once against that database instead of the CREATE TABLEs above:
--
--   ALTER TABLE products DROP COLUMN IF EXISTS store_id, DROP COLUMN IF EXISTS created_by;
--   ALTER TABLE customers DROP COLUMN IF EXISTS store_id, DROP COLUMN IF EXISTS created_by;
--   ALTER TABLE purchases DROP COLUMN IF EXISTS store_id, DROP COLUMN IF EXISTS created_by, DROP COLUMN IF EXISTS created_by_email;
--   ALTER TABLE payments DROP COLUMN IF EXISTS store_id, DROP COLUMN IF EXISTS created_by;
--   ALTER TABLE audit_log DROP COLUMN IF EXISTS store_id, DROP COLUMN IF EXISTS user_id;
--   DROP TABLE IF EXISTS app_users CASCADE;
--   DROP TABLE IF EXISTS stores CASCADE;
--   ALTER TABLE refresh_tokens DROP COLUMN IF EXISTS user_id;
-- ============================================================================
