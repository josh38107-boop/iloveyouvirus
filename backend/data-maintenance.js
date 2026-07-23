const CONFIRMATION_PHRASE = 'DELETE ALL ORDERS';
const RESET_PROTOCOL_VERSION = 1;
const OPERATION_ENTITIES = [
  'shift',
  'pos_order',
  'order_line',
  'payment',
  'receipt',
  'stock_snapshot',
  'order_inventory_add_on'
];

function httpError(status, message) {
  return Object.assign(new Error(message), { status });
}

function parseGeneration(value, message = 'Reload Data Maintenance before continuing.') {
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < 0) throw httpError(400, message);
  return parsed;
}

function validateResetRequest(input) {
  if (String(input?.confirmation || '') !== CONFIRMATION_PHRASE) {
    throw httpError(400, `Type ${CONFIRMATION_PHRASE} exactly to confirm.`);
  }
  if (input?.salesStopped !== true) {
    throw httpError(400, 'Confirm that sales are stopped on every POS device.');
  }
  return { expectedGeneration: parseGeneration(input?.expectedGeneration) };
}

function deletedCounts(row = {}) {
  return {
    orders: Number(row.orders || 0),
    orderLines: Number(row.order_lines || 0),
    payments: Number(row.payments || 0),
    receipts: Number(row.receipts || 0),
    shifts: Number(row.shifts || 0),
    stockSnapshots: Number(row.stock_snapshots || 0),
    orderAddOns: Number(row.order_add_ons || 0),
    promotionEntries: Number(row.promotion_entries || 0),
    rewardClaims: Number(row.reward_claims || 0),
    formSubmissions: Number(row.form_submissions || 0)
  };
}

function publicDevice(row, generation) {
  const protocolVersion = Number(row.reset_protocol_version || 0);
  const acknowledgedGeneration = Number(row.acknowledged_reset_generation || 0);
  return {
    id: row.id,
    name: row.name,
    role: row.role,
    status: row.status,
    lastSeenAt: row.last_seen_at == null ? null : Number(row.last_seen_at),
    resetProtocolVersion: protocolVersion,
    acknowledgedResetGeneration: acknowledgedGeneration,
    ready: row.status !== 'active' ||
      (protocolVersion >= RESET_PROTOCOL_VERSION && acknowledgedGeneration === generation)
  };
}

function createDataMaintenanceService(db, options = {}) {
  const branchId = options.branchId || 'main';
  const clock = options.now || Date.now;

  async function ensureState(client = db) {
    await client.query(`INSERT INTO operational_reset_state(branch_id)
      VALUES ($1) ON CONFLICT (branch_id) DO NOTHING`, [branchId]);
    const result = await client.query(
      'SELECT * FROM operational_reset_state WHERE branch_id=$1 LIMIT 1',
      [branchId]
    );
    return result.rows[0];
  }

  async function countOperationalRows(client = db) {
    const result = await client.query(`SELECT
      (SELECT COUNT(*) FROM pos_order) AS orders,
      (SELECT COUNT(*) FROM order_line) AS order_lines,
      (SELECT COUNT(*) FROM payment) AS payments,
      (SELECT COUNT(*) FROM receipt) AS receipts,
      (SELECT COUNT(*) FROM shift) AS shifts,
      (SELECT COUNT(*) FROM stock_snapshot) AS stock_snapshots,
      (SELECT COUNT(*) FROM order_inventory_add_on) AS order_add_ons,
      (SELECT COUNT(*) FROM promotion_entry) AS promotion_entries,
      (SELECT COUNT(*) FROM promotion_award) AS reward_claims,
      (SELECT COUNT(*) FROM promotion_form_submission) AS form_submissions`);
    return deletedCounts(result.rows[0]);
  }

  async function getStatus(client = db) {
    const state = await ensureState(client);
    const generation = Number(state.generation || 0);
    const [counts, devices, stock, campaign] = await Promise.all([
      countOperationalRows(client),
      client.query(`SELECT id,name,role,status,last_seen_at,reset_protocol_version,
          acknowledged_reset_generation
        FROM sync_device WHERE branch_id=$1 ORDER BY created_at`, [branchId]),
      client.query('SELECT COUNT(*) AS count FROM inventory_balance WHERE branch_id=$1', [branchId]),
      client.query(`SELECT enabled,orders_per_reward,cycle_progress,lifetime_order_count,
          google_form_url_template
        FROM promotion_campaign WHERE id='default' LIMIT 1`)
    ]);
    const deviceRows = devices.rows.map(row => publicDevice(row, generation));
    return {
      generation,
      resetAt: state.reset_at == null ? null : Number(state.reset_at),
      resetBy: state.reset_by || null,
      deletedCounts: state.deleted_counts || {},
      counts,
      devices: deviceRows,
      allActiveDevicesReady: deviceRows.filter(device => device.status === 'active')
        .every(device => device.ready),
      preserved: {
        inventoryBalanceRows: Number(stock.rows[0]?.count || 0),
        promotionEnabled: Boolean(campaign.rows[0]?.enabled),
        ordersPerReward: Number(campaign.rows[0]?.orders_per_reward || 0),
        cycleProgress: Number(campaign.rows[0]?.cycle_progress || 0),
        lifetimeOrderCount: Number(campaign.rows[0]?.lifetime_order_count || 0),
        promotionQrConfigured: Boolean(campaign.rows[0]?.google_form_url_template)
      }
    };
  }

  async function reset(input, adminUsername) {
    const { expectedGeneration } = validateResetRequest(input);
    const client = await db.pool.connect();
    try {
      await client.query('BEGIN');
      await ensureState(client);
      const locked = await client.query(
        'SELECT * FROM operational_reset_state WHERE branch_id=$1 FOR UPDATE',
        [branchId]
      );
      const currentGeneration = Number(locked.rows[0].generation || 0);
      if (currentGeneration !== expectedGeneration) {
        throw httpError(409, 'Data Maintenance changed on another screen. Reload before resetting.');
      }

      const devices = await client.query(`SELECT name,reset_protocol_version,
          acknowledged_reset_generation
        FROM sync_device WHERE branch_id=$1 AND status='active' ORDER BY name`, [branchId]);
      const unready = devices.rows.filter(row =>
        Number(row.reset_protocol_version || 0) < RESET_PROTOCOL_VERSION ||
        Number(row.acknowledged_reset_generation || 0) !== currentGeneration
      );
      if (unready.length) {
        throw httpError(409, `Update and sync these POS devices before resetting: ${unready.map(row => row.name).join(', ')}.`);
      }

      const counts = await countOperationalRows(client);
      const timestamp = clock();
      const nextGeneration = currentGeneration + 1;

      await client.query('DELETE FROM promotion_form_submission');
      await client.query('DELETE FROM promotion_award');
      await client.query('DELETE FROM promotion_entry');
      await client.query('DELETE FROM order_inventory_add_on');
      await client.query('DELETE FROM receipt');
      await client.query('DELETE FROM payment');
      await client.query('DELETE FROM order_line');
      await client.query('DELETE FROM stock_snapshot');
      await client.query('DELETE FROM pos_order');
      await client.query('DELETE FROM shift');
      await client.query(
        'DELETE FROM sync_change WHERE branch_id=$1 AND entity_type=ANY($2::text[])',
        [branchId, OPERATION_ENTITIES]
      );
      await client.query(`UPDATE promotion_campaign SET cycle_progress=0,
          lifetime_order_count=0, started_at=$1, updated_at=$1
        WHERE id='default'`, [timestamp]);
      await client.query(`UPDATE operational_reset_state SET generation=$2,reset_at=$3,
          reset_by=$4,deleted_counts=$5
        WHERE branch_id=$1`, [branchId, nextGeneration, timestamp, adminUsername, counts]);
      await client.query(`INSERT INTO operational_reset_audit
          (branch_id,generation,reset_at,reset_by,deleted_counts)
        VALUES ($1,$2,$3,$4,$5)`,
        [branchId, nextGeneration, timestamp, adminUsername, counts]);
      await client.query('COMMIT');
      return getStatus();
    } catch (error) {
      await client.query('ROLLBACK').catch(() => {});
      throw error;
    } finally {
      client.release();
    }
  }

  return { getStatus, reset };
}

module.exports = {
  CONFIRMATION_PHRASE,
  RESET_PROTOCOL_VERSION,
  OPERATION_ENTITIES,
  createDataMaintenanceService,
  parseGeneration,
  validateResetRequest,
  deletedCounts,
  publicDevice
};
