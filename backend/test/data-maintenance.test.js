const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {
  CONFIRMATION_PHRASE,
  RESET_PROTOCOL_VERSION,
  createDataMaintenanceService,
  validateResetRequest,
  duplicateDiagnostics,
  publicDevice
} = require('../data-maintenance');

const backendRoot = path.join(__dirname, '..');

function createResetDatabase({ devices = [], failOn = '' } = {}) {
  let generation = 0;
  const queries = [];
  const client = {
    async query(sql, values = []) {
      const normalized = String(sql).replace(/\s+/g, ' ').trim();
      queries.push({ sql: normalized, values });
      if (failOn && normalized.includes(failOn)) throw new Error('simulated database failure');
      if (normalized.startsWith('SELECT * FROM operational_reset_state')) {
        return { rows: [{ branch_id: 'main', generation, reset_at: null, reset_by: null, deleted_counts: {} }], rowCount: 1 };
      }
      if (normalized.includes('(SELECT COUNT(*) FROM pos_order)')) {
        return {
          rows: [{
            orders: '4', order_lines: '5', payments: '4', receipts: '4', shifts: '2',
            stock_snapshots: '3', order_add_ons: '1', promotion_entries: '4',
            reward_claims: '1', form_submissions: '1'
          }],
          rowCount: 1
        };
      }
      if (normalized.startsWith('WITH duplicate_payments AS')) {
        return {
          rows: [{
            duplicate_payments: '3',
            duplicate_payment_amount_cents: '34100',
            duplicate_order_lines: '5'
          }],
          rowCount: 1
        };
      }
      if (normalized.includes("FROM sync_device WHERE branch_id=$1 AND status='active'")) {
        return { rows: devices.filter(device => device.status === 'active'), rowCount: devices.length };
      }
      if (normalized.includes('FROM sync_device WHERE branch_id=$1 ORDER BY created_at')) {
        return { rows: devices, rowCount: devices.length };
      }
      if (normalized.startsWith('SELECT COUNT(*) AS count FROM inventory_balance')) {
        return { rows: [{ count: '12' }], rowCount: 1 };
      }
      if (normalized.includes("FROM promotion_campaign WHERE id='default'")) {
        return {
          rows: [{
            enabled: true,
            orders_per_reward: 300,
            cycle_progress: 7,
            lifetime_order_count: 42,
            google_form_url_template: 'https://example.com/promotion'
          }],
          rowCount: 1
        };
      }
      if (normalized.startsWith('UPDATE operational_reset_state')) {
        generation = Number(values[1]);
      }
      return { rows: [], rowCount: 1 };
    },
    release() {}
  };
  return {
    query: client.query,
    pool: { async connect() { return client; } },
    queries
  };
}

test('reset request requires the exact phrase, stopped sales, and a generation', () => {
  assert.deepEqual(validateResetRequest({
    confirmation: CONFIRMATION_PHRASE,
    salesStopped: true,
    expectedGeneration: 0
  }), { expectedGeneration: 0 });
  assert.throws(() => validateResetRequest({
    confirmation: 'delete all orders',
    salesStopped: true,
    expectedGeneration: 0
  }), /Type DELETE ALL ORDERS exactly/);
  assert.throws(() => validateResetRequest({
    confirmation: CONFIRMATION_PHRASE,
    salesStopped: false,
    expectedGeneration: 0
  }), /sales are stopped/);
  assert.throws(() => validateResetRequest({
    confirmation: CONFIRMATION_PHRASE,
    salesStopped: true,
    expectedGeneration: -1
  }), /Reload Data Maintenance/);
});

test('device readiness requires protocol support and the current generation', () => {
  const ready = publicDevice({
    id: 'one', name: 'Manager', role: 'manager', status: 'active',
    reset_protocol_version: RESET_PROTOCOL_VERSION,
    acknowledged_reset_generation: 3
  }, 3);
  assert.equal(ready.ready, true);
  assert.equal(publicDevice({ ...ready, reset_protocol_version: 0 }, 3).ready, false);
  assert.equal(publicDevice({ ...ready, acknowledged_reset_generation: 2 }, 3).ready, false);
  assert.equal(publicDevice({ ...ready, status: 'revoked' }, 3).ready, true);
});

test('duplicate diagnostics normalize database aggregate rows for the dashboard', () => {
  assert.deepEqual(duplicateDiagnostics({
    duplicate_payments: '3',
    duplicate_payment_amount_cents: '34100',
    duplicate_order_lines: '5'
  }), {
    duplicatePayments: 3,
    duplicatePaymentAmountCents: 34100,
    duplicateOrderLines: 5
  });
});

test('reset is one transaction, records an audit, and preserves stock/configuration', async () => {
  const db = createResetDatabase({
    devices: [{
      id: 'manager', name: 'Manager Tablet', role: 'manager', status: 'active',
      reset_protocol_version: 1, acknowledged_reset_generation: 0
    }]
  });
  const service = createDataMaintenanceService(db, { now: () => 123456789 });
  const result = await service.reset({
    confirmation: CONFIRMATION_PHRASE,
    salesStopped: true,
    expectedGeneration: 0
  }, 'admin');

  const sql = db.queries.map(query => query.sql);
  assert.ok(sql.indexOf('BEGIN') < sql.indexOf('COMMIT'));
  assert.ok(sql.some(query => query === 'DELETE FROM pos_order'));
  assert.ok(sql.some(query => query === 'DELETE FROM promotion_award'));
  assert.ok(sql.some(query => query.startsWith('DELETE FROM sync_change')));
  assert.ok(sql.some(query => query.startsWith('INSERT INTO operational_reset_audit')));
  assert.equal(result.generation, 1);
  assert.deepEqual(result.duplicateSummary, {
    duplicatePayments: 3,
    duplicatePaymentAmountCents: 34100,
    duplicateOrderLines: 5
  });
  assert.deepEqual(result.counts, {
    orders: 4, orderLines: 5, payments: 4, receipts: 4, shifts: 2,
    stockSnapshots: 3, orderAddOns: 1, promotionEntries: 4,
    rewardClaims: 1, formSubmissions: 1
  });

  const mutationSql = sql.filter(query => /^(DELETE|UPDATE)/.test(query)).join('\n');
  assert.doesNotMatch(mutationSql, /inventory_balance|inventory_event|menu_item|ingredient|sync_device\b|store_settings/);
  const campaignUpdate = sql.find(query => query.startsWith('UPDATE promotion_campaign'));
  assert.match(campaignUpdate, /cycle_progress=0/);
  assert.match(campaignUpdate, /lifetime_order_count=0/);
  assert.doesNotMatch(campaignUpdate, /enabled|orders_per_reward|google_form_url_template|eligible/);
});

test('stale generations and unready active devices return conflicts', async () => {
  const staleDb = createResetDatabase();
  const staleService = createDataMaintenanceService(staleDb);
  await assert.rejects(staleService.reset({
    confirmation: CONFIRMATION_PHRASE,
    salesStopped: true,
    expectedGeneration: 2
  }, 'admin'), error => error.status === 409);

  const unreadyDb = createResetDatabase({
    devices: [{
      name: 'Counter 1', status: 'active',
      reset_protocol_version: 0, acknowledged_reset_generation: 0
    }]
  });
  const unreadyService = createDataMaintenanceService(unreadyDb);
  await assert.rejects(unreadyService.reset({
    confirmation: CONFIRMATION_PHRASE,
    salesStopped: true,
    expectedGeneration: 0
  }, 'admin'), error => error.status === 409 && /Counter 1/.test(error.message));
});

test('database failures roll back without publishing a generation', async () => {
  const db = createResetDatabase({ failOn: 'DELETE FROM receipt' });
  const service = createDataMaintenanceService(db);
  await assert.rejects(service.reset({
    confirmation: CONFIRMATION_PHRASE,
    salesStopped: true,
    expectedGeneration: 0
  }, 'admin'), /simulated database failure/);
  const sql = db.queries.map(query => query.sql);
  assert.ok(sql.includes('ROLLBACK'));
  assert.ok(!sql.includes('COMMIT'));
  assert.ok(!sql.some(query => query.startsWith('UPDATE operational_reset_state')));
});

test('admin endpoints are authenticated and legacy operational uploads are reset-guarded', () => {
  const server = fs.readFileSync(path.join(backendRoot, 'server.js'), 'utf8');
  const cloud = fs.readFileSync(path.join(backendRoot, 'cloud.js'), 'utf8');
  assert.match(server, /app\.get\('\/admin\/data-maintenance', adminAuthenticate/);
  assert.match(server, /app\.post\('\/admin\/data-maintenance\/reset', adminAuthenticate/);
  assert.match(server, /app\.all\('\/rest\/v1\/:table', authenticate, guardLegacyOperationalWrite/);
  assert.match(server, /guardOperationalRpc/);
  assert.match(cloud, /app\.get\('\/sync\/v1\/reset-state', deviceAuth/);
  assert.match(cloud, /code: 'OPERATIONAL_RESET_REQUIRED'/);
  assert.match(cloud, /FOR SHARE/);
});

test('migration is additive and stores only reset audit metadata', () => {
  const migration = fs.readFileSync(path.join(backendRoot, 'migrations', '009_operational_reset.sql'), 'utf8');
  assert.match(migration, /CREATE TABLE IF NOT EXISTS operational_reset_state/);
  assert.match(migration, /CREATE TABLE IF NOT EXISTS operational_reset_audit/);
  assert.match(migration, /reset_protocol_version/);
  assert.match(migration, /acknowledged_reset_generation/);
  assert.doesNotMatch(migration, /\b(?:DELETE|TRUNCATE|DROP)\b/i);
});

test('Android applies a reset before sync while preserving stock and enrollment', () => {
  const projectRoot = path.resolve(backendRoot, '..');
  const repositories = fs.readFileSync(path.join(
    projectRoot, 'app', 'src', 'main', 'java', 'com', 'kape', 'coffeepos', 'data', 'Repositories.kt'
  ), 'utf8');
  const sync = fs.readFileSync(path.join(
    projectRoot, 'app', 'src', 'main', 'java', 'com', 'kape', 'coffeepos', 'data', 'SupabaseSyncManager.kt'
  ), 'utf8');
  const main = fs.readFileSync(path.join(
    projectRoot, 'app', 'src', 'main', 'java', 'com', 'kape', 'coffeepos', 'MainActivity.kt'
  ), 'utf8');
  const gradle = fs.readFileSync(path.join(projectRoot, 'app', 'build.gradle.kts'), 'utf8');

  const resetFunction = repositories.slice(
    repositories.indexOf('internal suspend fun clearOperationalHistoryPreservingInventory'),
    repositories.indexOf('data class MenuCatalog')
  );
  assert.match(resetFunction, /clearOrders\(\)/);
  assert.match(resetFunction, /clearShifts\(\)/);
  assert.doesNotMatch(resetFunction, /clearInventoryAdjustments|clearIngredients|quantityOnHand|deleteAll/);

  const syncBody = sync.slice(
    sync.indexOf('private suspend fun syncNowInternal'),
    sync.indexOf('// HTTP compatibility layer')
  );
  assert.ok(
    syncBody.indexOf('applyOperationalResetIfRequired()') < syncBody.indexOf('refreshChangeCursor()'),
    'reset must be checked before any download or upload'
  );
  assert.match(sync, /X-Reset-Protocol-Version/);
  assert.match(sync, /X-Operational-Reset-Generation/);
  assert.match(sync, /putLong\("operational_reset_generation"/);
  assert.doesNotMatch(main, /Reset All Operations & Inventory|showResetConfirmDialog/);
  assert.match(main, /Data Maintenance on the admin website/);
  assert.match(gradle, /versionCode\s*=\s*10/);
  assert.match(gradle, /versionName\s*=\s*"1\.9"/);
});
