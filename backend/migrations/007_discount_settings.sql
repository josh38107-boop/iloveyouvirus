CREATE TABLE IF NOT EXISTS discount_rule (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  percent DOUBLE PRECISION NOT NULL CHECK (percent > 0 AND percent <= 100),
  scope TEXT NOT NULL CHECK (scope IN ('item', 'order')),
  requires_reference BOOLEAN NOT NULL DEFAULT FALSE,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  sort_order INTEGER NOT NULL DEFAULT 0,
  created_at BIGINT NOT NULL DEFAULT 0,
  updated_at BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_discount_rule_name_ci
  ON discount_rule (LOWER(name));

ALTER TABLE store_settings
  ADD COLUMN IF NOT EXISTS discount_settings_updated_at BIGINT NOT NULL DEFAULT 0;

ALTER TABLE pos_order
  ADD COLUMN IF NOT EXISTS discount_rule_id TEXT,
  ADD COLUMN IF NOT EXISTS discount_category TEXT,
  ADD COLUMN IF NOT EXISTS discount_percent DOUBLE PRECISION,
  ADD COLUMN IF NOT EXISTS discount_scope TEXT,
  ADD COLUMN IF NOT EXISTS discount_reference TEXT;
