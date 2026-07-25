require('dotenv').config();
const express = require('express');
const cors = require('cors');
const path = require('path');
const db = require('./db');
const rpc = require('./rpc');
const { createCloud } = require('./cloud');
const { createInventoryService } = require('./inventory');
const { createMenuService } = require('./menu');
const { createEmployeeService } = require('./employees');
const { createDiscountService } = require('./discounts');
const { createPaymentVoidService } = require('./payment-void');
const { createDataMaintenanceService } = require('./data-maintenance');

process.env.TOKEN_PEPPER = process.env.TOKEN_PEPPER || 'KapeTokenPepper2024SecretKey';
process.env.SESSION_SECRET = process.env.SESSION_SECRET || 'KapeSessionSecret2024KeySecret';
process.env.ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'KapeAdmin2024';

const app = express();
const PORT = process.env.PORT || 3000;
const cloud = createCloud(db);
const inventory = createInventoryService(db, { branchId: process.env.DEFAULT_BRANCH_ID || 'main' });
const menu = createMenuService(db, { branchId: process.env.DEFAULT_BRANCH_ID || 'main' });
const employees = createEmployeeService(db, { branchId: process.env.DEFAULT_BRANCH_ID || 'main' });
const discounts = createDiscountService(db, { branchId: process.env.DEFAULT_BRANCH_ID || 'main' });
const paymentVoid = createPaymentVoidService(db, { branchId: process.env.DEFAULT_BRANCH_ID || 'main' });
const dataMaintenance = createDataMaintenanceService(db, { branchId: process.env.DEFAULT_BRANCH_ID || 'main' });
const promotion = rpc.createPromotionService(db);

// ─── Allowed tables (whitelist for security) ─────────────────────────────────
const ALLOWED_TABLES = new Set([
  'sync_device_authority', 'sync_tombstone',
  'menu_category', 'menu_item', 'modifier_group', 'modifier_option',
  'menu_item_modifier_group', 'ingredient', 'recipe_ingredient',
  'modifier_recipe_ingredient', 'payment_method', 'discount_rule', 'employee',
  'store_settings', 'shift', 'pos_order', 'order_line', 'payment',
  'receipt', 'stock_snapshot', 'inventory_balance', 'order_inventory_add_on'
]);
const RESET_GUARDED_TABLES = new Set([
  'shift', 'pos_order', 'order_line', 'payment', 'receipt',
  'stock_snapshot', 'order_inventory_add_on', 'inventory_balance'
]);
const RESET_GUARDED_RPCS = new Set([
  'apply_inventory_event', 'get_promotion_result', 'reserve_promotion_claim',
  'release_promotion_claim', 'finalize_promotion_claim', 'mark_promotion_printed'
]);

// ─── Middleware ───────────────────────────────────────────────────────────────
app.set('trust proxy', 1);
app.use(cors(cloud.corsOptions));
app.use(express.json({ limit: '10mb' }));
cloud.attachRoutes(app);

// ─── Serve Admin Dashboard as static files ────────────────────────────────────
app.use(express.static(path.join(__dirname, '..', 'dashboard')));

const authenticate = cloud.legacyAuth;
const adminAuthenticate = cloud.adminAuth;

async function requireCurrentResetGeneration(req, res, next) {
  try {
    const branchId = req.syncDevice?.branch_id || process.env.DEFAULT_BRANCH_ID || 'main';
    const state = await db.query(
      'SELECT generation FROM operational_reset_state WHERE branch_id=$1 LIMIT 1',
      [branchId]
    );
    const currentGeneration = Number(state.rows[0]?.generation || 0);
    const rawGeneration = req.headers['x-operational-reset-generation'];
    if (currentGeneration === 0 && (rawGeneration == null || rawGeneration === '')) return next();
    const reportedGeneration = Number(rawGeneration);
    if (!Number.isSafeInteger(reportedGeneration) || reportedGeneration < 0 ||
        reportedGeneration !== currentGeneration) {
      return res.status(409).json({
        error: 'Install the latest POS APK and sync before uploading operational data.',
        code: 'OPERATIONAL_RESET_REQUIRED',
        currentGeneration
      });
    }
    return next();
  } catch (err) {
    return next(err);
  }
}

function guardLegacyOperationalWrite(req, res, next) {
  if (req.method === 'GET' || !RESET_GUARDED_TABLES.has(req.params.table)) return next();
  return requireCurrentResetGeneration(req, res, next);
}

function guardOperationalRpc(req, res, next) {
  if (!RESET_GUARDED_RPCS.has(req.params.fn)) return next();
  return requireCurrentResetGeneration(req, res, next);
}

// ─── Parse PostgREST-style filter query params ────────────────────────────────
function parseFilters(query) {
  const SKIP = new Set(['select', 'order', 'limit', 'offset']);
  const filters = [];
  for (const [key, rawVal] of Object.entries(query)) {
    if (SKIP.has(key)) continue;
    const dotIdx = rawVal.indexOf('.');
    if (dotIdx === -1) continue;
    const op = rawVal.substring(0, dotIdx);
    const val = rawVal.substring(dotIdx + 1);
    let sqlOp;
    switch (op) {
      case 'eq':  sqlOp = '=';  break;
      case 'neq': sqlOp = '!='; break;
      case 'gte': sqlOp = '>='; break;
      case 'gt':  sqlOp = '>';  break;
      case 'lte': sqlOp = '<='; break;
      case 'lt':  sqlOp = '<';  break;
      case 'like': sqlOp = 'LIKE'; break;
      default: continue;
    }
    filters.push({ col: key, op: sqlOp, val });
  }
  return filters;
}

// ─── Build WHERE clause from filters ─────────────────────────────────────────
function buildWhere(filters, startIdx = 1) {
  if (!filters.length) return { clause: '', values: [] };
  const values = [];
  const parts = filters.map(({ col, op, val }) => {
    values.push(val);
    return `"${col}" ${op} $${startIdx + values.length - 1}`;
  });
  return { clause: 'WHERE ' + parts.join(' AND '), values };
}

// ─── Parse columns for SELECT ─────────────────────────────────────────────────
function parseSelect(selectParam) {
  if (!selectParam || selectParam === '*') return '*';
  return selectParam.split(',').map(c => `"${c.trim()}"`).join(', ');
}

// ─── Generic GET handler ──────────────────────────────────────────────────────
async function handleGet(req, res, table) {
  try {
    const cols = parseSelect(req.query.select);
    const filters = parseFilters(req.query);
    const { clause, values } = buildWhere(filters);
    const sql = `SELECT ${cols} FROM "${table}" ${clause}`;
    const result = await db.query(sql, values);
    return res.json(result.rows);
  } catch (err) {
    console.error(`GET ${table}:`, err.message);
    return res.status(500).json({ error: err.message });
  }
}

// ─── Generic POST/UPSERT handler ─────────────────────────────────────────────
async function handlePost(req, res, table) {
  try {
    const prefer = req.headers['prefer'] || '';
    const ignoreConflict = prefer.includes('ignore-duplicates');
    const onConflict = req.query.on_conflict || null;
    const body = Array.isArray(req.body) ? req.body : [req.body];
    if (!body.length || !body[0]) return res.status(201).json([]);

    const results = [];
    for (const row of body) {
      const keys = Object.keys(row);
      const cols = keys.map(k => `"${k}"`).join(', ');
      const placeholders = keys.map((_, i) => `$${i + 1}`).join(', ');
      const vals = keys.map(k => row[k]);

      let sql;
      if (onConflict) {
        const conflictCols = onConflict.split(',').map(c => `"${c.trim()}"`).join(', ');
        if (ignoreConflict) {
          sql = `INSERT INTO "${table}" (${cols}) VALUES (${placeholders}) ON CONFLICT (${conflictCols}) DO NOTHING RETURNING *`;
        } else {
          const updateSet = keys
            .filter(k => !onConflict.split(',').map(c => c.trim()).includes(k))
            .map((k, i) => `"${k}" = EXCLUDED."${k}"`)
            .join(', ');
          if (updateSet) {
            sql = `INSERT INTO "${table}" (${cols}) VALUES (${placeholders}) ON CONFLICT (${conflictCols}) DO UPDATE SET ${updateSet} RETURNING *`;
          } else {
            sql = `INSERT INTO "${table}" (${cols}) VALUES (${placeholders}) ON CONFLICT (${conflictCols}) DO NOTHING RETURNING *`;
          }
        }
      } else {
        sql = `INSERT INTO "${table}" (${cols}) VALUES (${placeholders}) RETURNING *`;
      }

      const result = await db.query(sql, vals);
      if (result.rows.length) {
        results.push(result.rows[0]);
        if (req.path.startsWith('/admin/data/')) await cloud.recordAdminChange(table, result.rows[0]);
      }
    }

    return res.status(201).json(results);
  } catch (err) {
    console.error(`POST ${table}:`, err.message);
    return res.status(500).json({ error: err.message });
  }
}

// ─── Generic PATCH handler ────────────────────────────────────────────────────
async function handlePatch(req, res, table) {
  try {
    const filters = parseFilters(req.query);
    const body = req.body || {};
    const keys = Object.keys(body);
    if (!keys.length) return res.status(400).json({ error: 'No fields to update' });

    const setClause = keys.map((k, i) => `"${k}" = $${i + 1}`).join(', ');
    const vals = keys.map(k => body[k]);
    const { clause, values } = buildWhere(filters, vals.length + 1);

    const sql = `UPDATE "${table}" SET ${setClause} ${clause} RETURNING *`;
    const result = await db.query(sql, [...vals, ...values]);
    if (req.path.startsWith('/admin/data/')) {
      for (const row of result.rows) await cloud.recordAdminChange(table, row);
    }
    return res.json(result.rows);
  } catch (err) {
    console.error(`PATCH ${table}:`, err.message);
    return res.status(500).json({ error: err.message });
  }
}

// ─── Generic DELETE handler ───────────────────────────────────────────────────
async function handleDelete(req, res, table) {
  try {
    const filters = parseFilters(req.query);
    const { clause, values } = buildWhere(filters);
    if (!clause) return res.status(400).json({ error: 'Delete requires filters' });
    const sql = `DELETE FROM "${table}" ${clause} RETURNING *`;
    const result = await db.query(sql, values);
    if (req.path.startsWith('/admin/data/')) {
      for (const row of result.rows) await cloud.recordAdminChange(table, row, 'delete');
    }
    return res.json(result.rows);
  } catch (err) {
    console.error(`DELETE ${table}:`, err.message);
    return res.status(500).json({ error: err.message });
  }
}

// ─── PostgREST-compatible table routes ───────────────────────────────────────
app.all('/rest/v1/:table', authenticate, guardLegacyOperationalWrite, async (req, res) => {
  const { table } = req.params;
  if (!ALLOWED_TABLES.has(table)) {
    return res.status(404).json({ error: `Table '${table}' not found` });
  }
  if (table === 'payment_method' && req.method !== 'GET') {
    return res.status(403).json({ error: 'Manage payment methods in the admin website.' });
  }
  if (table === 'store_settings' && req.method !== 'GET' && req.syncDevice) {
    const stripWebsiteFields = row => {
      const cleaned = { ...row };
      delete cleaned.void_refund_pin;
      delete cleaned.payment_void_settings_updated_at;
      return cleaned;
    };
    req.body = Array.isArray(req.body) ? req.body.map(stripWebsiteFields) : stripWebsiteFields(req.body || {});
  }
  const managerTables = new Set(['sync_device_authority', 'sync_tombstone', 'menu_category', 'menu_item', 'modifier_group',
    'modifier_option', 'menu_item_modifier_group', 'ingredient', 'recipe_ingredient', 'modifier_recipe_ingredient',
    'payment_method', 'discount_rule', 'employee', 'store_settings']);
  if (req.syncDevice && req.method !== 'GET' && managerTables.has(table) && req.syncDevice.role !== 'manager') {
    return res.status(403).json({ error: 'Manager device role required' });
  }
  switch (req.method) {
    case 'GET':    return handleGet(req, res, table);
    case 'POST':   return handlePost(req, res, table);
    case 'PATCH':  return handlePatch(req, res, table);
    case 'DELETE': return handleDelete(req, res, table);
    default: return res.status(405).json({ error: 'Method not allowed' });
  }
});

app.all('/admin/data/:table', adminAuthenticate, async (req, res) => {
  const { table } = req.params;
  if (table === 'employee') return res.status(404).json({ error: 'Use the dedicated employee management API.' });
  if (table === 'discount_rule') return res.status(404).json({ error: 'Use the dedicated discount settings API.' });
  if (table === 'payment_method') return res.status(404).json({ error: 'Use the dedicated Payment & Void Settings API.' });
  if (!ALLOWED_TABLES.has(table)) return res.status(404).json({ error: `Table '${table}' not found` });
  switch (req.method) {
    case 'GET': return handleGet(req, res, table);
    case 'POST': return handlePost(req, res, table);
    case 'PATCH': return handlePatch(req, res, table);
    case 'DELETE': return handleDelete(req, res, table);
    default: return res.status(405).json({ error: 'Method not allowed' });
  }
});

// ─── RPC routes ───────────────────────────────────────────────────────────────
async function handleRpc(req, res) {
  const { fn } = req.params;
  const handler = rpc[fn];
  if (!handler) return res.status(404).json({ error: `RPC '${fn}' not found` });
  try {
    const normalized = {};
    for (const [key, value] of Object.entries(req.body || {})) normalized[key.startsWith('p_') ? key.slice(2) : key] = value;
    if (req.syncDevice) {
      normalized.authenticated_device_id = req.syncDevice.id;
      normalized.device_id = req.syncDevice.id;
      normalized.branch_id = req.syncDevice.branch_id;
    }
    const result = await handler(normalized, db);
    return res.json(result);
  } catch (err) {
    console.error(`RPC ${fn}:`, err.message);
    return res.status(500).json({ error: err.message });
  }
}

function rejectDevicePromotionUpdate(req, res, next) {
  if (req.params.fn === 'update_promotion_config') {
    return res.status(403).json({ error: 'Manage Free Drink Promotion settings in the admin website.' });
  }
  return next();
}

app.post('/rest/v1/rpc/:fn', authenticate, rejectDevicePromotionUpdate, guardOperationalRpc, handleRpc);
app.post('/sync/v1/rpc/:fn', cloud.deviceAuth, guardOperationalRpc, (req, res, next) => {
  if (req.params.fn === 'update_promotion_config') {
    return res.status(403).json({ error: 'Manage Free Drink Promotion settings in the admin website.' });
  }
  return handleRpc(req, res, next);
});

// ─── Admin Dashboard API routes ───────────────────────────────────────────────

function reportRange(daysParam) {
  const parsed = parseInt(daysParam, 10);
  const days = Math.min(Math.max(Number.isFinite(parsed) ? parsed : 1, 1), 365);
  const from = new Date();
  from.setHours(0, 0, 0, 0);
  from.setDate(from.getDate() - (days - 1));
  return { days, fromMs: from.getTime() };
}

function paymentCategory(row) {
  const category = String(row.payment_category || '').toUpperCase();
  if (category === 'CASH' || category === 'ONLINE') return category;
  const method = String(row.method || '').toLowerCase();
  if (method === 'cash') return 'CASH';
  if (method === 'online' || method === 'gcash') return 'ONLINE';
  return '';
}

// GET /admin/stats?days=1 — report summary for the selected date range
app.get('/admin/stats', adminAuthenticate, async (req, res) => {
  try {
    const { days, fromMs } = reportRange(req.query.days);
    const [summaryRes, topItemsRes, paymentRes, shiftsRes, discountRes] = await Promise.all([
      db.query(`
        SELECT COUNT(*) as count,
               COALESCE(SUM(subtotal_cents), 0) as gross,
               COALESCE(SUM(total_cents), 0) as net
        FROM pos_order
        WHERE created_at >= $1 AND status != 'void'
      `, [fromMs]),
      db.query(`
        SELECT name, SUM(quantity) as qty,
               COALESCE(SUM(ol.quantity * ol.unit_price_cents - COALESCE(ol.discount_cents, 0)), 0) as revenue
        FROM order_line ol
        JOIN pos_order o ON o.id = ol.order_id
        WHERE o.created_at >= $1 AND o.status != 'void'
        GROUP BY name ORDER BY qty DESC
      `, [fromMs]),
      db.query(`
        SELECT method, payment_category, SUM(amount_cents) as total
        FROM payment p
        JOIN pos_order o ON o.id = p.order_id
        WHERE o.created_at >= $1 AND o.status != 'void'
        GROUP BY method, payment_category
        ORDER BY method
      `, [fromMs]),
      db.query(`
        SELECT s.id, s.starting_cash_cents, s.ending_cash_cents,
               s.cash_added_cents, s.cash_removed_cents,
               COALESCE(SUM(CASE
                 WHEN UPPER(COALESCE(p.payment_category, '')) = 'CASH'
                   OR (COALESCE(p.payment_category, '') = '' AND LOWER(p.method) = 'cash')
                 THEN p.amount_cents ELSE 0 END), 0) as cash_sales
        FROM shift s
        LEFT JOIN pos_order o ON o.shift_id = s.id AND o.status != 'void'
        LEFT JOIN payment p ON p.order_id = o.id
        WHERE s.opened_at >= $1
        GROUP BY s.id, s.starting_cash_cents, s.ending_cash_cents,
                 s.cash_added_cents, s.cash_removed_cents, s.opened_at
        ORDER BY s.opened_at
      `, [fromMs]),
      db.query(`
        SELECT COALESCE(discount_category, 'Discount') AS name,
               COALESCE(discount_scope, 'item') AS scope,
               COUNT(*) AS order_count,
               COALESCE(SUM(discount_cents), 0) AS amount_cents
        FROM pos_order
        WHERE created_at >= $1 AND status != 'void' AND discount_cents > 0
        GROUP BY COALESCE(discount_category, 'Discount'), COALESCE(discount_scope, 'item')
        ORDER BY amount_cents DESC
      `, [fromMs])
    ]);

    const payments = paymentRes.rows;
    const cashSales = payments
      .filter(row => paymentCategory(row) === 'CASH')
      .reduce((sum, row) => sum + parseInt(row.total || 0), 0);
    const onlinePayments = payments
      .filter(row => paymentCategory(row) === 'ONLINE')
      .reduce((sum, row) => sum + parseInt(row.total || 0), 0);
    const cashDrawer = shiftsRes.rows.reduce((totals, shift) => {
      const starting = parseInt(shift.starting_cash_cents || 0);
      const added = parseInt(shift.cash_added_cents || 0);
      const removed = parseInt(shift.cash_removed_cents || 0);
      const shiftCashSales = parseInt(shift.cash_sales || 0);
      const expected = starting + shiftCashSales + added - removed;
      const actual = shift.ending_cash_cents == null
        ? expected
        : parseInt(shift.ending_cash_cents || 0);
      totals.startingCash += starting;
      totals.expectedCashEnding += expected;
      totals.actualCashEnding += actual;
      totals.cashAdded += added;
      totals.cashRemoved += removed;
      return totals;
    }, { startingCash: 0, expectedCashEnding: 0, actualCashEnding: 0, cashAdded: 0, cashRemoved: 0 });

    cashDrawer.onlinePayments = onlinePayments;
    cashDrawer.totalCashAndOnline = cashDrawer.expectedCashEnding + onlinePayments;
    cashDrawer.difference = cashDrawer.actualCashEnding - cashDrawer.expectedCashEnding;
    cashDrawer.cashSales = cashSales;

    res.json({
      days,
      ordersToday: parseInt(summaryRes.rows[0].count),
      revenueToday: parseInt(summaryRes.rows[0].net),
      grossSales: parseInt(summaryRes.rows[0].gross),
      netSales: parseInt(summaryRes.rows[0].net),
      topItems: topItemsRes.rows,
      discountBreakdown: discountRes.rows,
      paymentBreakdown: payments,
      cashDrawer
    });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// GET /admin/sales?days=7 — sales chart data
app.get('/admin/sales', adminAuthenticate, async (req, res) => {
  try {
    const { fromMs } = reportRange(req.query.days || 7);
    const result = await db.query(`
      SELECT
        TO_CHAR(TO_TIMESTAMP(created_at / 1000), 'YYYY-MM-DD') as date,
        COUNT(*) as orders,
        COALESCE(SUM(total_cents), 0) as revenue
      FROM pos_order
      WHERE created_at >= $1 AND status != 'void'
      GROUP BY date ORDER BY date
    `, [fromMs]);
    res.json(result.rows);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// GET /admin/orders?limit=50&offset=0 — paginated orders
app.get('/admin/orders', adminAuthenticate, async (req, res) => {
  try {
    const limit = Math.min(parseInt(req.query.limit) || 50, 200);
    const offset = parseInt(req.query.offset) || 0;
    const result = await db.query(`
      SELECT o.*, e.name as employee_name
      FROM pos_order o
      LEFT JOIN employee e ON e.id = o.employee_id
      ORDER BY o.created_at DESC
      LIMIT $1 OFFSET $2
    `, [limit, offset]);
    res.json(result.rows);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// GET /admin/inventory — stock levels with low-stock flag
app.get('/admin/inventory', adminAuthenticate, async (req, res) => {
  try {
    res.json(await inventory.list());
  } catch (err) {
    return res.status(err.status || 500).json({ error: err.status ? err.message : 'Internal server error' });
  }
});

app.post('/admin/inventory', adminAuthenticate, async (req, res) => {
  try { res.status(201).json(await inventory.create(req.body)); }
  catch (err) { res.status(err.status || 500).json({ error: err.status ? err.message : 'Internal server error' }); }
});

app.put('/admin/inventory/:id', adminAuthenticate, async (req, res) => {
  try { res.json(await inventory.update(req.params.id, req.body)); }
  catch (err) { res.status(err.status || 500).json({ error: err.status ? err.message : 'Internal server error' }); }
});

app.delete('/admin/inventory/:id', adminAuthenticate, async (req, res) => {
  try { res.json(await inventory.remove(req.params.id)); }
  catch (err) { res.status(err.status || 500).json({ error: err.status ? err.message : 'Internal server error' }); }
});

app.get('/admin/menu', adminAuthenticate, async (req, res) => {
  try { res.json(await menu.list()); }
  catch (err) { console.error('GET /admin/menu:', err); res.status(err.status || 500).json({ error: err.status ? err.message : 'Internal server error' }); }
});

app.post('/admin/menu/categories', adminAuthenticate, async (req, res) => {
  try { res.status(201).json(await menu.createCategory(req.body)); }
  catch (err) { console.error('POST /admin/menu/categories:', err); res.status(err.status || 500).json({ error: err.status ? err.message : 'Internal server error' }); }
});

app.put('/admin/menu/categories/:id', adminAuthenticate, async (req, res) => {
  try { res.json(await menu.updateCategory(req.params.id, req.body)); }
  catch (err) { console.error('PUT /admin/menu/categories/:id:', err); res.status(err.status || 500).json({ error: err.status ? err.message : 'Internal server error' }); }
});

app.delete('/admin/menu/categories/:id', adminAuthenticate, async (req, res) => {
  try { res.json(await menu.deleteCategory(req.params.id)); }
  catch (err) { console.error('DELETE /admin/menu/categories/:id:', err); res.status(err.status || 500).json({ error: err.status ? err.message : 'Internal server error' }); }
});

app.post('/admin/menu/items', adminAuthenticate, async (req, res) => {
  try { res.status(201).json(await menu.createItem(req.body)); }
  catch (err) { console.error('POST /admin/menu/items:', err); res.status(err.status || 500).json({ error: err.status ? err.message : 'Internal server error' }); }
});

app.put('/admin/menu/items/:id', adminAuthenticate, async (req, res) => {
  try { res.json(await menu.updateItem(req.params.id, req.body)); }
  catch (err) { console.error('PUT /admin/menu/items/:id:', err); res.status(err.status || 500).json({ error: err.status ? err.message : 'Internal server error' }); }
});

app.delete('/admin/menu/items/:id', adminAuthenticate, async (req, res) => {
  try { res.json(await menu.deleteItem(req.params.id)); }
  catch (err) { console.error('DELETE /admin/menu/items/:id:', err); res.status(err.status || 500).json({ error: err.status ? err.message : 'Internal server error' }); }
});

app.post('/admin/menu/modifier-groups', adminAuthenticate, async (req, res) => {
  try { res.status(201).json(await menu.createModifierGroup(req.body)); }
  catch (err) { console.error('POST /admin/menu/modifier-groups:', err); res.status(err.status || 500).json({ error: err.status ? err.message : 'Internal server error' }); }
});

app.put('/admin/menu/modifier-groups/:id', adminAuthenticate, async (req, res) => {
  try { res.json(await menu.updateModifierGroup(req.params.id, req.body)); }
  catch (err) { console.error('PUT /admin/menu/modifier-groups/:id:', err); res.status(err.status || 500).json({ error: err.status ? err.message : 'Internal server error' }); }
});

app.delete('/admin/menu/modifier-groups/:id', adminAuthenticate, async (req, res) => {
  try { res.json(await menu.deleteModifierGroup(req.params.id)); }
  catch (err) { console.error('DELETE /admin/menu/modifier-groups/:id:', err); res.status(err.status || 500).json({ error: err.status ? err.message : 'Internal server error' }); }
});

app.post('/admin/menu/modifier-groups/:id/options', adminAuthenticate, async (req, res) => {
  try { res.status(201).json(await menu.createModifierOption(req.params.id, req.body)); }
  catch (err) { console.error('POST /admin/menu/modifier-groups/:id/options:', err); res.status(err.status || 500).json({ error: err.status ? err.message : 'Internal server error' }); }
});

app.put('/admin/menu/modifier-options/:id', adminAuthenticate, async (req, res) => {
  try { res.json(await menu.updateModifierOption(req.params.id, req.body)); }
  catch (err) { console.error('PUT /admin/menu/modifier-options/:id:', err); res.status(err.status || 500).json({ error: err.status ? err.message : 'Internal server error' }); }
});

app.delete('/admin/menu/modifier-options/:id', adminAuthenticate, async (req, res) => {
  try { res.json(await menu.deleteModifierOption(req.params.id)); }
  catch (err) { console.error('DELETE /admin/menu/modifier-options/:id:', err); res.status(err.status || 500).json({ error: err.status ? err.message : 'Internal server error' }); }
});

app.get('/admin/promotion', adminAuthenticate, async (req, res) => {
  try { res.json(await promotion.getConfig()); }
  catch (err) { console.error('GET /admin/promotion:', err); res.status(err.status || 500).json({ error: err.status ? err.message : 'Internal server error' }); }
});

app.get('/admin/discount-settings', adminAuthenticate, async (req, res) => {
  try { res.json(await discounts.getSettings()); }
  catch (err) { console.error('GET /admin/discount-settings:', err); res.status(err.status || 500).json({ error: err.status ? err.message : 'Internal server error' }); }
});

app.put('/admin/discount-settings', adminAuthenticate, async (req, res) => {
  try { res.json(await discounts.updateBenefits(req.body || {})); }
  catch (err) { console.error('PUT /admin/discount-settings:', err); res.status(err.status || 500).json({ error: err.status ? err.message : 'Internal server error' }); }
});

app.post('/admin/discount-settings/custom', adminAuthenticate, async (req, res) => {
  try { res.status(201).json(await discounts.createRule(req.body || {})); }
  catch (err) { console.error('POST /admin/discount-settings/custom:', err); res.status(err.status || 500).json({ error: err.status ? err.message : 'Internal server error' }); }
});

app.put('/admin/discount-settings/custom/:id', adminAuthenticate, async (req, res) => {
  try { res.json(await discounts.updateRule(req.params.id, req.body || {})); }
  catch (err) { console.error('PUT /admin/discount-settings/custom/:id:', err); res.status(err.status || 500).json({ error: err.status ? err.message : 'Internal server error' }); }
});

app.get('/admin/payment-void-settings', adminAuthenticate, async (req, res) => {
  try { res.json(await paymentVoid.getSettings()); }
  catch (err) { console.error('GET /admin/payment-void-settings:', err); res.status(err.status || 500).json({ error: err.status ? err.message : 'Internal server error' }); }
});

app.put('/admin/payment-void-settings/pin', adminAuthenticate, async (req, res) => {
  try { res.json(await paymentVoid.updatePin(req.body || {})); }
  catch (err) { console.error('PUT /admin/payment-void-settings/pin:', err); res.status(err.status || 500).json({ error: err.status ? err.message : 'Internal server error' }); }
});

app.post('/admin/payment-void-settings/methods', adminAuthenticate, async (req, res) => {
  try { res.status(201).json(await paymentVoid.createMethod(req.body || {})); }
  catch (err) { console.error('POST /admin/payment-void-settings/methods:', err); res.status(err.status || 500).json({ error: err.status ? err.message : 'Internal server error' }); }
});

app.put('/admin/payment-void-settings/methods/:id', adminAuthenticate, async (req, res) => {
  try { res.json(await paymentVoid.updateMethod(req.params.id, req.body || {})); }
  catch (err) { console.error('PUT /admin/payment-void-settings/methods/:id:', err); res.status(err.status || 500).json({ error: err.status ? err.message : 'Internal server error' }); }
});

app.delete('/admin/payment-void-settings/methods/:id', adminAuthenticate, async (req, res) => {
  try { res.json(await paymentVoid.deleteMethod(req.params.id, req.body || {})); }
  catch (err) { console.error('DELETE /admin/payment-void-settings/methods/:id:', err); res.status(err.status || 500).json({ error: err.status ? err.message : 'Internal server error' }); }
});

app.put('/admin/promotion', adminAuthenticate, async (req, res) => {
  try { res.json(await promotion.updateConfig(req.body || {})); }
  catch (err) { console.error('PUT /admin/promotion:', err); res.status(err.status || 500).json({ error: err.status ? err.message : 'Internal server error' }); }
});

app.get('/admin/promotion/claims', adminAuthenticate, async (req, res) => {
  try {
    res.json(await promotion.listClaims({
      status: req.query.status,
      limit: req.query.limit == null ? undefined : Number(req.query.limit),
      offset: req.query.offset == null ? undefined : Number(req.query.offset)
    }));
  } catch (err) {
    console.error('GET /admin/promotion/claims:', err);
    res.status(err.status || 500).json({ error: err.status ? err.message : 'Internal server error' });
  }
});

app.get('/admin/data-maintenance', adminAuthenticate, async (req, res) => {
  try { res.json(await dataMaintenance.getStatus()); }
  catch (err) {
    console.error('GET /admin/data-maintenance:', err);
    res.status(err.status || 500).json({ error: err.status ? err.message : 'Internal server error' });
  }
});

app.post('/admin/data-maintenance/reset', adminAuthenticate, async (req, res) => {
  try { res.json(await dataMaintenance.reset(req.body || {}, req.admin.sub)); }
  catch (err) {
    console.error('POST /admin/data-maintenance/reset:', err);
    res.status(err.status || 500).json({ error: err.status ? err.message : 'Internal server error' });
  }
});

app.get('/admin/employees', adminAuthenticate, async (req, res) => {
  try { res.json(await employees.list()); }
  catch (err) { console.error('GET /admin/employees:', err); res.status(err.status || 500).json({ error: err.status ? err.message : 'Internal server error' }); }
});

app.post('/admin/employees', adminAuthenticate, async (req, res) => {
  try { res.status(201).json(await employees.create(req.body)); }
  catch (err) { console.error('POST /admin/employees:', err); res.status(err.status || 500).json({ error: err.status ? err.message : 'Internal server error' }); }
});

app.put('/admin/employees/:id', adminAuthenticate, async (req, res) => {
  try { res.json(await employees.update(req.params.id, req.body)); }
  catch (err) { console.error('PUT /admin/employees/:id:', err); res.status(err.status || 500).json({ error: err.status ? err.message : 'Internal server error' }); }
});

app.delete('/admin/employees/:id', adminAuthenticate, async (req, res) => {
  try { res.json(await employees.deactivate(req.params.id)); }
  catch (err) { console.error('DELETE /admin/employees/:id:', err); res.status(err.status || 500).json({ error: err.status ? err.message : 'Internal server error' }); }
});

// ─── Health check ─────────────────────────────────────────────────────────────
app.get('/health', (req, res) => res.json({ status: 'ok', timestamp: Date.now() }));
app.get('/ready', async (req, res) => {
  try {
    const requiredTables = ['sync_device', 'sync_enrollment', 'sync_mutation', 'sync_change',
      'sync_device_authority', 'sync_tombstone', 'inventory_balance', 'operational_reset_state',
      'operational_reset_audit'];
    const schema = await db.query(`SELECT table_name, column_name, data_type
      FROM information_schema.columns
      WHERE table_schema = 'public' AND table_name = ANY($1::text[])`, [requiredTables]);
    const tables = new Set(schema.rows.map(row => row.table_name));
    const syncDeviceColumns = new Set(schema.rows
      .filter(row => row.table_name === 'sync_device')
      .map(row => row.column_name));
    const invalidBranchTypes = schema.rows.some(row =>
      ['sync_device_authority', 'sync_tombstone', 'inventory_balance', 'operational_reset_state',
        'operational_reset_audit'].includes(row.table_name) &&
      row.column_name === 'branch_id' && row.data_type !== 'text');
    const missingResetColumns = ['reset_protocol_version', 'acknowledged_reset_generation']
      .some(column => !syncDeviceColumns.has(column));
    if (requiredTables.some(table => !tables.has(table)) || invalidBranchTypes || missingResetColumns) {
      return res.status(503).json({ status: 'migration_required', command: 'cd backend && npm run migrate' });
    }
    res.json({ status: 'ready', timestamp: Date.now() });
  } catch {
    res.status(503).json({ status: 'not_ready' });
  }
});

app.use((err, req, res, next) => {
  console.error(`${req.method} ${req.path}:`, err);
  if (res.headersSent) return next(err);
  if (err.code === '42P01') {
    return res.status(503).json({ error: 'Render database migration required', command: 'cd backend && npm run migrate' });
  }
  if (err.code === '22P02' && /uuid/i.test(err.message || '')) {
    return res.status(503).json({ error: 'Render branch schema migration required', command: 'cd backend && npm run migrate' });
  }
  res.status(err.status || 500).json({ error: err.status ? err.message : 'Internal server error' });
});

// ─── Start server ─────────────────────────────────────────────────────────────
app.listen(PORT, () => {
  console.log(`✅ Kanlungan Coffee Garage API running on port ${PORT}`);
});
