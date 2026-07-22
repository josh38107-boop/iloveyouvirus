-- Android stores complementary exclusions as a comma-separated string.
-- Normalize fresh schemas that originally created this column as TEXT[].
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema='public' AND table_name='menu_item'
      AND column_name='complementary_exclusions' AND data_type='ARRAY'
  ) THEN
    ALTER TABLE menu_item ALTER COLUMN complementary_exclusions DROP DEFAULT;
    ALTER TABLE menu_item ALTER COLUMN complementary_exclusions TYPE TEXT
      USING array_to_string(complementary_exclusions, ',');
    ALTER TABLE menu_item ALTER COLUMN complementary_exclusions SET DEFAULT '';
  END IF;
  UPDATE menu_item SET complementary_exclusions='' WHERE complementary_exclusions IS NULL;
END $$;
