ALTER TABLE store_settings
  ADD COLUMN IF NOT EXISTS business_day_cutoff_minutes INTEGER NOT NULL DEFAULT 120;

ALTER TABLE store_settings
  ADD COLUMN IF NOT EXISTS business_day_settings_updated_at BIGINT NOT NULL DEFAULT 0;

UPDATE store_settings
SET business_day_cutoff_minutes = 120
WHERE business_day_cutoff_minutes IS NULL;
