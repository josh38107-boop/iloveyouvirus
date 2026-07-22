-- Finish normalizing columns retained from the pre-Render schema. Android sends
-- deletion times as epoch milliseconds, and inventory writes do not include the
-- legacy updated_at column.

DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = 'sync_tombstone'
      AND column_name = 'deleted_at'
      AND data_type = 'timestamp with time zone'
  ) THEN
    ALTER TABLE sync_tombstone ALTER COLUMN deleted_at DROP DEFAULT;
    ALTER TABLE sync_tombstone ALTER COLUMN deleted_at TYPE BIGINT
      USING (EXTRACT(EPOCH FROM deleted_at) * 1000)::BIGINT;
  END IF;
END $$;

DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = 'inventory_balance'
      AND column_name = 'updated_at'
  ) THEN
    ALTER TABLE inventory_balance ALTER COLUMN updated_at SET DEFAULT 0;
  END IF;
END $$;
