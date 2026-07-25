CREATE TABLE IF NOT EXISTS audit_log (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  branch_id TEXT NOT NULL DEFAULT 'main',
  event_type TEXT NOT NULL,
  actor_name TEXT NOT NULL DEFAULT 'System',
  description TEXT NOT NULL,
  amount_cents INTEGER,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  metadata JSONB DEFAULT '{}'::jsonb
);

CREATE INDEX IF NOT EXISTS idx_audit_log_created_at ON audit_log (created_at DESC);
