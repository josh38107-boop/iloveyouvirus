require('dotenv').config();
const fs = require('fs');
const path = require('path');
const db = require('./db');

async function migrate() {
  const directory = path.join(__dirname, 'migrations');
  const files = fs.readdirSync(directory).filter(name => name.endsWith('.sql')).sort();
  const client = await db.pool.connect();
  try {
    await client.query('SELECT pg_advisory_lock($1)', [20260722]);
    await client.query(`CREATE TABLE IF NOT EXISTS schema_migration (
      version TEXT PRIMARY KEY, applied_at BIGINT NOT NULL
    )`);
    for (const file of files) {
      const applied = await client.query('SELECT 1 FROM schema_migration WHERE version = $1', [file]);
      if (applied.rowCount) continue;
      await client.query('BEGIN');
      try {
        await client.query(fs.readFileSync(path.join(directory, file), 'utf8'));
        await client.query('INSERT INTO schema_migration(version, applied_at) VALUES ($1, $2)', [file, Date.now()]);
        await client.query('COMMIT');
        console.log(`Applied migration ${file}`);
      } catch (error) {
        await client.query('ROLLBACK');
        throw error;
      }
    }
  } finally {
    await client.query('SELECT pg_advisory_unlock($1)', [20260722]).catch(() => {});
    client.release();
  }
}

migrate()
  .then(() => db.pool.end())
  .catch(async error => {
    console.error(error);
    await db.pool.end().catch(() => {});
    process.exit(1);
  });

