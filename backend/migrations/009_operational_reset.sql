CREATE TABLE IF NOT EXISTS operational_reset_state (
  branch_id TEXT PRIMARY KEY,
  generation BIGINT NOT NULL DEFAULT 0 CHECK (generation >= 0),
  reset_at BIGINT,
  reset_by TEXT,
  deleted_counts JSONB NOT NULL DEFAULT '{}'::JSONB
);

CREATE TABLE IF NOT EXISTS operational_reset_audit (
  branch_id TEXT NOT NULL,
  generation BIGINT NOT NULL CHECK (generation > 0),
  reset_at BIGINT NOT NULL,
  reset_by TEXT NOT NULL,
  deleted_counts JSONB NOT NULL DEFAULT '{}'::JSONB,
  PRIMARY KEY (branch_id, generation)
);

ALTER TABLE sync_device
  ADD COLUMN IF NOT EXISTS reset_protocol_version INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS acknowledged_reset_generation BIGINT NOT NULL DEFAULT 0;

INSERT INTO operational_reset_state(branch_id)
VALUES ('main')
ON CONFLICT (branch_id) DO NOTHING;
