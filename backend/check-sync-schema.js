require('dotenv').config();
const db = require('./db');

const requiredColumns = {
  sync_device: ['id', 'branch_id', 'hardware_id', 'name', 'role', 'token_hash', 'status'],
  sync_enrollment: ['id', 'code_hash', 'branch_id', 'device_name', 'role', 'expires_at'],
  sync_mutation: ['mutation_id', 'device_id', 'result', 'created_at'],
  sync_change: ['sequence', 'branch_id', 'entity_type', 'entity_id', 'operation', 'payload'],
  sync_device_authority: ['branch_id', 'manager_device_id', 'manager_device_name', 'revision', 'updated_at'],
  sync_tombstone: ['branch_id', 'entity_type', 'entity_id', 'deleted_by_device', 'deleted_at'],
  inventory_balance: ['branch_id', 'ingredient_id', 'quantity']
};

db.query(`SELECT table_name, column_name, data_type
  FROM information_schema.columns
  WHERE table_schema = 'public' AND table_name = ANY($1::text[])
  ORDER BY table_name, ordinal_position`, [Object.keys(requiredColumns)])
  .then(result => {
    const actual = result.rows.reduce((tables, row) => {
      (tables[row.table_name] ||= {})[row.column_name] = row.data_type;
      return tables;
    }, {});
    const missing = {};
    for (const [table, columns] of Object.entries(requiredColumns)) {
      const absent = columns.filter(column => !actual[table]?.[column]);
      if (absent.length) missing[table] = absent;
    }
    for (const table of ['sync_device_authority', 'sync_tombstone', 'inventory_balance']) {
      if (actual[table]?.branch_id && actual[table].branch_id !== 'text') {
        (missing[table] ||= []).push(`branch_id must be text (found ${actual[table].branch_id})`);
      }
    }
    if (actual.sync_tombstone?.deleted_at && actual.sync_tombstone.deleted_at !== 'bigint') {
      (missing.sync_tombstone ||= []).push(`deleted_at must be bigint (found ${actual.sync_tombstone.deleted_at})`);
    }
    console.log(JSON.stringify({ ok: Object.keys(missing).length === 0, missing, actual }, null, 2));
    if (Object.keys(missing).length) process.exitCode = 1;
  })
  .catch(error => { console.error(error.message); process.exitCode = 1; })
  .finally(() => db.pool.end());
