ALTER TABLE hidden_activity_history
  ADD COLUMN IF NOT EXISTS shift_device_id TEXT,
  ADD COLUMN IF NOT EXISTS shift_id TEXT,
  ADD COLUMN IF NOT EXISTS event_type TEXT;

CREATE INDEX IF NOT EXISTS idx_hidden_activity_history_shift
  ON hidden_activity_history(shift_device_id, shift_id)
  WHERE shift_device_id IS NOT NULL AND shift_id IS NOT NULL;
