CREATE TABLE IF NOT EXISTS promotion_campaign (
  id TEXT PRIMARY KEY DEFAULT 'default' CHECK (id = 'default'),
  enabled BOOLEAN NOT NULL DEFAULT FALSE,
  orders_per_reward INTEGER NOT NULL DEFAULT 300
    CHECK (orders_per_reward BETWEEN 1 AND 100000),
  cycle_progress INTEGER NOT NULL DEFAULT 0 CHECK (cycle_progress >= 0),
  lifetime_order_count BIGINT NOT NULL DEFAULT 0 CHECK (lifetime_order_count >= 0),
  started_at BIGINT NOT NULL DEFAULT ((EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::BIGINT),
  google_form_url_template TEXT NOT NULL DEFAULT '',
  claim_validity_days INTEGER NOT NULL DEFAULT 30 CHECK (claim_validity_days BETWEEN 1 AND 3650),
  form_webhook_secret TEXT NOT NULL DEFAULT
    (REPLACE(gen_random_uuid()::TEXT, '-', '') || REPLACE(gen_random_uuid()::TEXT, '-', '')),
  updated_at BIGINT NOT NULL DEFAULT ((EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::BIGINT),
  CONSTRAINT promotion_campaign_progress_below_interval CHECK (cycle_progress < orders_per_reward)
);

CREATE TABLE IF NOT EXISTS promotion_entry (
  order_id TEXT PRIMARY KEY REFERENCES pos_order(id) ON DELETE RESTRICT,
  campaign_id TEXT NOT NULL REFERENCES promotion_campaign(id),
  sequence_number BIGINT NOT NULL,
  cycle_position INTEGER NOT NULL,
  interval_at_entry INTEGER NOT NULL,
  is_winner BOOLEAN NOT NULL DEFAULT FALSE,
  counted_at BIGINT NOT NULL DEFAULT ((EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::BIGINT),
  UNIQUE (campaign_id, sequence_number)
);

CREATE TABLE IF NOT EXISTS promotion_award (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  campaign_id TEXT NOT NULL REFERENCES promotion_campaign(id),
  order_id TEXT NOT NULL UNIQUE REFERENCES promotion_entry(order_id),
  sequence_number BIGINT NOT NULL,
  interval_at_award INTEGER NOT NULL,
  claim_code TEXT NOT NULL UNIQUE,
  status TEXT NOT NULL DEFAULT 'issued'
    CHECK (status IN ('issued', 'reserved', 'claimed', 'cancelled')),
  issued_at BIGINT NOT NULL,
  expires_at BIGINT NOT NULL,
  printed_at BIGINT,
  print_count INTEGER NOT NULL DEFAULT 0 CHECK (print_count >= 0),
  print_requested_at BIGINT,
  last_print_device_id TEXT,
  reserved_at BIGINT,
  reserved_device_id TEXT,
  reserved_employee_id TEXT,
  reserved_cart_token TEXT,
  redemption_order_id TEXT UNIQUE,
  claimed_at BIGINT,
  claimed_employee_id TEXT,
  requires_manager_review BOOLEAN NOT NULL DEFAULT FALSE,
  cancelled_at BIGINT,
  cancellation_reason TEXT
);

CREATE TABLE IF NOT EXISTS promotion_eligible_item (
  campaign_id TEXT NOT NULL DEFAULT 'default' REFERENCES promotion_campaign(id),
  item_id TEXT NOT NULL,
  created_at BIGINT NOT NULL DEFAULT ((EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::BIGINT),
  PRIMARY KEY (campaign_id, item_id)
);

CREATE TABLE IF NOT EXISTS promotion_form_submission (
  google_response_id TEXT PRIMARY KEY,
  claim_code TEXT NOT NULL REFERENCES promotion_award(claim_code),
  submitted_at BIGINT NOT NULL,
  received_at BIGINT NOT NULL DEFAULT ((EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::BIGINT)
);

CREATE INDEX IF NOT EXISTS idx_promotion_award_campaign_status
  ON promotion_award(campaign_id, status);
CREATE INDEX IF NOT EXISTS idx_promotion_award_reserved_cart_token
  ON promotion_award(reserved_cart_token) WHERE reserved_cart_token IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_promotion_entry_campaign_sequence
  ON promotion_entry(campaign_id, sequence_number);

INSERT INTO promotion_campaign(id)
VALUES ('default')
ON CONFLICT (id) DO NOTHING;
