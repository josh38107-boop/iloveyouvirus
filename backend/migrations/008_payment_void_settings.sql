ALTER TABLE payment_method
  ADD COLUMN IF NOT EXISTS created_at BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS updated_at BIGINT NOT NULL DEFAULT 0;

ALTER TABLE store_settings
  ADD COLUMN IF NOT EXISTS payment_void_settings_updated_at BIGINT NOT NULL DEFAULT 0;

UPDATE store_settings
SET void_refund_pin = '1234'
WHERE void_refund_pin IS NULL OR void_refund_pin = '';

INSERT INTO payment_method
  (id, name, enabled, is_system, payment_category, created_at, updated_at)
VALUES
  ('cash', 'Cash', TRUE, TRUE, 'CASH', 0, 0),
  ('gcash', 'Online', TRUE, TRUE, 'ONLINE', 0, 0),
  ('split', 'Split', TRUE, TRUE, NULL, 0, 0),
  ('complimentary', 'Complimentary', TRUE, TRUE, NULL, 0, 0)
ON CONFLICT (id) DO UPDATE SET
  name = EXCLUDED.name,
  enabled = TRUE,
  is_system = TRUE,
  payment_category = EXCLUDED.payment_category;
