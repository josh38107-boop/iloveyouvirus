CREATE TABLE IF NOT EXISTS closed_shift_adjustment (
  device_id TEXT NOT NULL,
  id TEXT NOT NULL,
  current_shift_device_id TEXT NOT NULL,
  current_shift_id TEXT NOT NULL,
  original_order_id TEXT NOT NULL,
  original_shift_device_id TEXT,
  original_shift_id TEXT NOT NULL,
  amount_cents INTEGER NOT NULL DEFAULT 0,
  type TEXT NOT NULL,
  reason TEXT NOT NULL DEFAULT '',
  staff_id TEXT,
  created_at BIGINT NOT NULL,
  PRIMARY KEY (device_id, id)
);

CREATE INDEX IF NOT EXISTS idx_closed_shift_adjustment_current_shift
  ON closed_shift_adjustment(current_shift_device_id, current_shift_id);

CREATE INDEX IF NOT EXISTS idx_closed_shift_adjustment_created_at
  ON closed_shift_adjustment(created_at);
