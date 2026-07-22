CREATE TABLE IF NOT EXISTS schema_migration (
  version TEXT PRIMARY KEY,
  applied_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS sync_device (
  id TEXT PRIMARY KEY,
  branch_id TEXT NOT NULL,
  hardware_id TEXT NOT NULL,
  name TEXT NOT NULL,
  role TEXT NOT NULL CHECK (role IN ('manager', 'counter')),
  token_hash TEXT NOT NULL UNIQUE,
  status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'revoked')),
  last_seen_at BIGINT,
  created_at BIGINT NOT NULL,
  revoked_at BIGINT,
  UNIQUE (branch_id, hardware_id)
);

CREATE TABLE IF NOT EXISTS sync_enrollment (
  id TEXT PRIMARY KEY,
  code_hash TEXT NOT NULL UNIQUE,
  branch_id TEXT NOT NULL,
  device_name TEXT NOT NULL,
  role TEXT NOT NULL CHECK (role IN ('manager', 'counter')),
  expires_at BIGINT NOT NULL,
  used_at BIGINT,
  created_by TEXT NOT NULL,
  created_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS sync_mutation (
  mutation_id TEXT PRIMARY KEY,
  device_id TEXT REFERENCES sync_device(id),
  result JSONB NOT NULL,
  created_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS sync_change (
  sequence BIGSERIAL PRIMARY KEY,
  branch_id TEXT NOT NULL,
  entity_type TEXT NOT NULL,
  entity_id TEXT NOT NULL,
  operation TEXT NOT NULL CHECK (operation IN ('upsert', 'delete', 'inventory_event')),
  payload JSONB,
  device_id TEXT REFERENCES sync_device(id),
  created_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS sync_inventory_event (
  event_id TEXT PRIMARY KEY,
  branch_id TEXT NOT NULL,
  device_id TEXT NOT NULL REFERENCES sync_device(id),
  ingredient_id TEXT NOT NULL,
  delta_quantity DOUBLE PRECISION NOT NULL,
  reason TEXT,
  created_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_sync_device_last_seen ON sync_device(last_seen_at DESC);
CREATE INDEX IF NOT EXISTS idx_sync_enrollment_expiry ON sync_enrollment(expires_at);
CREATE INDEX IF NOT EXISTS idx_sync_mutation_device ON sync_mutation(device_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_sync_change_branch_cursor ON sync_change(branch_id, sequence);
CREATE INDEX IF NOT EXISTS idx_sync_change_entity ON sync_change(entity_type, entity_id);
