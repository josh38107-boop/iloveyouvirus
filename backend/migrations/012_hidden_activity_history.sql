CREATE TABLE IF NOT EXISTS hidden_activity_history (
  event_id TEXT PRIMARY KEY,
  hidden_by TEXT NOT NULL DEFAULT 'admin',
  hidden_at BIGINT NOT NULL
);
