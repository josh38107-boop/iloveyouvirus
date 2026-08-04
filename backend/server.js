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
const {
  MANILA_TIME_ZONE,
  DEFAULT_BUSINESS_DAY_CUTOFF_MINUTES,
  normalizeCutoffMinutes,
  currentBusinessDayWindow,
  reportWindowForRange
} = require('./report-range');
const helmet = require('helmet');
const rateLimit = require('express-rate-limit');

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
  'receipt', 'stock_snapshot', 'inventory_balance', 'order_inventory_add_on', 'audit_log'
]);

const RESET_GUARDED_TABLES = new Set([
  'shift', 'pos_order', 'order_line', 'payment', 'receipt',
  'stock_snapshot', 'order_inventory_add_on', 'inventory_balance'
]);
const RESET_GUARDED_RPCS = new Set([
  'apply_inventory_event', 'get_promotion_result', 'reserve_promotion_claim',
  'release_promotion_claim', 'finalize_promotion_claim', 'mark_promotion_printed'
]);

const HIDEABLE_HAPPENING_ID_PATTERN = /^shift-(open|close)-([^|]+)\|(.+)$/;

async function getBusinessDayCutoffMinutes() {
  const result = await db.query(`SELECT business_day_cutoff_minutes
    FROM store_settings WHERE id='store' LIMIT 1`);
  return normalizeCutoffMinutes(result.rows[0]?.business_day_cutoff_minutes);
}

// ─── Middleware ───────────────────────────────────────────────────────────────
app.set('trust proxy', 1);

// ── 1. Security Headers (Helmet) ─────────────────────────────────────────────
// Sets 15+ HTTP headers that block XSS, clickjacking, MIME sniffing, etc.
app.use(helmet({
  contentSecurityPolicy: false, // dashboard uses inline scripts; loosen only this
  crossOriginEmbedderPolicy: false,
}));

// ── 2. Global DDoS / Flood Rate Limiter ──────────────────────────────────────
// Max 200 requests per minute per IP across all routes
const globalLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 200,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'Too many requests. Please slow down.' },
  skip: (req) => req.path === '/health' || req.path === '/ready',
});
app.use(globalLimiter);

// ── 3. Auth Route Brute-Force Limiter ────────────────────────────────────────
// Max 10 login attempts per 15 minutes per IP — blocks password guessing
const authLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  max: 10,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'Too many login attempts. Try again in 15 minutes.' },
});
app.use('/rest/v1/auth', authLimiter);
app.use('/admin/login', authLimiter);
app.use('/admin/auth', authLimiter);

// ── 4. Sync / Write Route Limiter ────────────────────────────────────────────
// Devices can push 120 sync mutations per minute — prevents data flooding
const syncLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 120,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'Sync rate limit exceeded.' },
});
app.use('/rest/v1/sync', syncLimiter);
app.use('/rest/v1/rpc', syncLimiter);

// ── 5. Admin Route Limiter ────────────────────────────────────────────────────
// Extra strict for admin endpoints — max 60 per minute
const adminLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 60,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'Admin rate limit exceeded.' },
});
app.use('/admin', adminLimiter);

// ── 6. Block Suspicious Request Patterns ─────────────────────────────────────
// Catches common attack probe patterns (SQL injection, path traversal, script injection)
const SUSPICIOUS_PATTERNS = [
  /(\'|\")(\s)*(or|and)(\s)+/i,       // SQL injection: ' OR 1=1
  /union(\s)+select/i,                  // SQL UNION attack
  /\.\.\/|\.\.\\/,                     // Path traversal: ../
  /<script[^>]*>/i,                     // XSS script tag
  /javascript:/i,                       // XSS javascript: URI
  /on(load|error|click|mouse)\s*=/i,   // XSS event handlers
  /exec\s*\(/i,                         // Code execution
  /eval\s*\(/i,                         // JS eval injection
];

app.use((req, res, next) => {
  const checkStr = decodeURIComponent(
    (req.url + JSON.stringify(req.query) + JSON.stringify(req.body || ''))
  );
  for (const pattern of SUSPICIOUS_PATTERNS) {
    if (pattern.test(checkStr)) {
      console.warn(`[SECURITY] Blocked suspicious request from ${req.ip}: ${req.method} ${req.path}`);
      return res.status(400).json({ error: 'Bad request.' });
    }
  }
  next();
});

// ── 7. Block Oversized Payloads (prevents memory exhaustion) ─────────────────
// Already set below via express.json limit — also block at middleware level
app.use((req, res, next) => {
  const contentLength = parseInt(req.headers['content-length'] || '0', 10);
  if (contentLength > 5 * 1024 * 1024) { // 5 MB hard cap
    return res.status(413).json({ error: 'Payload too large.' });
  }
  next();
});

app.use(cors(cloud.corsOptions));
app.use(express.json({ limit: '2mb' })); // reduced from 10mb
cloud.attachRoutes(app);

// ─── Serve Admin Dashboard as static files ────────────────────────────────────
app.use(express.static(path.join(__dirname, '..', 'dashboard')));

const authenticate = cloud.legacyAuth;
const adminAuthenticate = cloud.adminAuth;

function latestApkMetadata() {
  return {
    configured: Boolean(process.env.APK_DOWNLOAD_URL),
    versionName: process.env.APK_VERSION_NAME || null,
    versionCode: process.env.APK_VERSION_CODE || null
  };
}

app.get('/admin/apk/latest/info', adminAuthenticate, (req, res) => {
  res.json(latestApkMetadata());
});

app.get('/admin/apk/latest', adminAuthenticate, (req, res) => {
  const apkUrl = process.env.APK_DOWNLOAD_URL;
  if (!apkUrl) return res.status(404).json({ error: 'Latest APK is not configured' });

  try {
    const parsedUrl = new URL(apkUrl);
    if (!['http:', 'https:'].includes(parsedUrl.protocol)) throw new Error('Unsupported APK URL protocol');
    return res.redirect(302, parsedUrl.toString());
  } catch (err) {
    console.error('GET /admin/apk/latest:', err.message);
    return res.status(400).json({ error: 'Latest APK URL is invalid. Check APK_DOWNLOAD_URL in Render.' });
  }
});

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
      delete cleaned.business_day_cutoff_minutes;
      delete cleaned.business_day_settings_updated_at;
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

async function reportRange(daysParam, fromDate, toDate) {
  const cutoffMinutes = await getBusinessDayCutoffMinutes();
  return reportWindowForRange({ daysParam, fromDate, toDate, cutoffMinutes });
}

function paymentCategory(row) {
  const category = String(row.payment_category || '').toUpperCase();
  if (category === 'CASH' || category === 'ONLINE') return category;
  const method = String(row.method || '').toLowerCase();
  if (method === 'cash') return 'CASH';
  if (method === 'online' || method === 'gcash') return 'ONLINE';
  return '';
}

function nonComplimentaryOrderPredicate(orderAlias) {
  return `NOT EXISTS (
          SELECT 1 FROM payment complimentary_payment
          WHERE complimentary_payment.order_id = ${orderAlias}.id
            AND LOWER(complimentary_payment.method) = 'complimentary'
        )`;
}

app.get('/admin/business-day-settings', adminAuthenticate, async (req, res) => {
  try {
    const settingsRes = await db.query(`SELECT id, business_day_cutoff_minutes, business_day_settings_updated_at
      FROM store_settings WHERE id='store' LIMIT 1`);
    const settings = settingsRes.rows[0] || {
      id: 'store',
      business_day_cutoff_minutes: DEFAULT_BUSINESS_DAY_CUTOFF_MINUTES,
      business_day_settings_updated_at: 0
    };
    const cutoffMinutes = normalizeCutoffMinutes(settings.business_day_cutoff_minutes);
    const window = currentBusinessDayWindow(cutoffMinutes);
    const openShiftsRes = await db.query(`
      SELECT device_id, id, employee_id, opened_at, starting_cash_cents, cash_added_cents, cash_removed_cents
      FROM shift
      WHERE closed_at IS NULL
      ORDER BY opened_at DESC
    `);
    res.json({
      timezone: MANILA_TIME_ZONE,
      cutoffMinutes,
      updatedAt: Number(settings.business_day_settings_updated_at || 0),
      currentBusinessDate: window.businessDate,
      currentWindow: { startMs: window.startMs, endMs: window.endMs },
      openShifts: openShiftsRes.rows
    });
  } catch (err) {
    console.error('GET /admin/business-day-settings:', err);
    res.status(500).json({ error: err.message });
  }
});

app.put('/admin/business-day-settings', adminAuthenticate, async (req, res) => {
  let client;
  try {
    const cutoffMinutes = Number(req.body?.cutoffMinutes);
    if (!Number.isInteger(cutoffMinutes) || cutoffMinutes < 0 || cutoffMinutes > 1439) {
      return res.status(400).json({ error: 'cutoffMinutes must be an integer from 0 through 1439.' });
    }
    client = await db.pool.connect();
    await client.query('BEGIN');
    await client.query(`INSERT INTO store_settings(id, store_name, tax_rate_percent, tip_presets, receipt_footer)
      VALUES ('store', 'Kanlungan Coffee Garage', 0, '{}', '')
      ON CONFLICT (id) DO NOTHING`);
    const current = await client.query(`SELECT * FROM store_settings WHERE id='store' FOR UPDATE`);
    const currentCutoff = normalizeCutoffMinutes(current.rows[0]?.business_day_cutoff_minutes);
    const openShifts = await client.query('SELECT device_id, id, opened_at FROM shift WHERE closed_at IS NULL');
    if (cutoffMinutes !== currentCutoff && openShifts.rowCount > 0) {
      await client.query('ROLLBACK');
      return res.status(409).json({
        error: 'Close and sync every open shift before changing the business-day cutoff.',
        openShifts: openShifts.rows
      });
    }
    const updatedAt = cutoffMinutes === currentCutoff
      ? Number(current.rows[0]?.business_day_settings_updated_at || 0)
      : Date.now();
    const updated = await client.query(`UPDATE store_settings
      SET business_day_cutoff_minutes=$1, business_day_settings_updated_at=$2
      WHERE id='store' RETURNING *`, [cutoffMinutes, updatedAt]);
    await client.query(`INSERT INTO sync_change(branch_id, entity_type, entity_id, operation, payload, device_id, created_at)
      VALUES ($1, 'store_settings', 'store', 'upsert', $2, NULL, $3)`,
      [process.env.DEFAULT_BRANCH_ID || 'main', updated.rows[0], Date.now()]);
    await client.query('COMMIT');
    const window = currentBusinessDayWindow(cutoffMinutes);
    res.json({
      timezone: MANILA_TIME_ZONE,
      cutoffMinutes,
      updatedAt,
      currentBusinessDate: window.businessDate,
      currentWindow: { startMs: window.startMs, endMs: window.endMs },
      openShifts: []
    });
  } catch (err) {
    if (client) await client.query('ROLLBACK').catch(() => {});
    console.error('PUT /admin/business-day-settings:', err);
    res.status(500).json({ error: err.message });
  } finally {
    if (client) client.release();
  }
});

// GET /admin/stats?days=1&fromDate=YYYY-MM-DD&toDate=YYYY-MM-DD — report summary
app.get('/admin/stats', adminAuthenticate, async (req, res) => {
  try {
    const range = await reportRange(req.query.days, req.query.fromDate, req.query.toDate);
    const { days, fromMs, toMs } = range;
    await ensureHiddenActivityHistoryTable();
    const [summaryRes, topItemsRes, orderSummaryRes, paymentRes, shiftsRes, discountRes] = await Promise.all([
      db.query(`
        SELECT COUNT(*) as count,
               COALESCE(SUM(subtotal_cents), 0) as gross,
               COALESCE(SUM(total_cents), 0) as net
        FROM pos_order o
        WHERE o.created_at >= $1 AND o.created_at < $2 AND o.status = 'paid'
          AND ${nonComplimentaryOrderPredicate('o')}
          AND NOT EXISTS (
            SELECT 1 FROM hidden_activity_history h
            WHERE h.shift_device_id = o.shift_device_id AND h.shift_id = o.shift_id::text
          )
      `, [fromMs, toMs]),
      db.query(`
        WITH deduped_order_line AS (
          SELECT DISTINCT ON (
            order_id, item_id, name, quantity, unit_price_cents,
            COALESCE(modifiers::text, ''), COALESCE(notes, ''),
            COALESCE(discount_category, ''), COALESCE(discount_cents, 0)
          ) *
          FROM order_line
          ORDER BY
            order_id, item_id, name, quantity, unit_price_cents,
            COALESCE(modifiers::text, ''), COALESCE(notes, ''),
            COALESCE(discount_category, ''), COALESCE(discount_cents, 0),
            device_id, id
        )
        ,
        deduped_payment AS (
          SELECT DISTINCT ON (
            order_id, method, COALESCE(payment_category, ''), amount_cents,
            COALESCE(amount_tendered_cents, amount_cents), COALESCE(change_cents, 0), created_at
          ) *
          FROM payment
          ORDER BY
            order_id, method, COALESCE(payment_category, ''), amount_cents,
            COALESCE(amount_tendered_cents, amount_cents), COALESCE(change_cents, 0), created_at,
            device_id, id
        ),
        item_totals AS (
          SELECT ol.name, SUM(ol.quantity) as qty,
                 COALESCE(SUM(ol.quantity * ol.unit_price_cents - COALESCE(ol.discount_cents, 0)), 0) as revenue
          FROM deduped_order_line ol
          JOIN pos_order o ON o.id = ol.order_id
          WHERE o.created_at >= $1 AND o.created_at < $2 AND o.status = 'paid'
            AND ${nonComplimentaryOrderPredicate('o')}
            AND NOT EXISTS (
              SELECT 1 FROM hidden_activity_history h
              WHERE h.shift_device_id = o.shift_device_id AND h.shift_id = o.shift_id::text
            )
          GROUP BY ol.name
        ),
        item_payment_modes AS (
          SELECT ol.name,
                 BOOL_OR(UPPER(COALESCE(p.payment_category, '')) = 'CASH'
                   OR (COALESCE(p.payment_category, '') = '' AND LOWER(p.method) = 'cash')) as has_cash,
                 BOOL_OR(UPPER(COALESCE(p.payment_category, '')) = 'ONLINE'
                   OR (COALESCE(p.payment_category, '') = '' AND LOWER(p.method) IN ('online', 'gcash'))) as has_online
          FROM deduped_order_line ol
          JOIN pos_order o ON o.id = ol.order_id
          LEFT JOIN deduped_payment p ON p.order_id = o.id
          WHERE o.created_at >= $1 AND o.created_at < $2 AND o.status = 'paid'
            AND ${nonComplimentaryOrderPredicate('o')}
            AND NOT EXISTS (
              SELECT 1 FROM hidden_activity_history h
              WHERE h.shift_device_id = o.shift_device_id AND h.shift_id = o.shift_id::text
            )
          GROUP BY ol.name
        )
        SELECT t.name, t.qty, t.revenue,
               CASE
                 WHEN COALESCE(m.has_cash, false) AND COALESCE(m.has_online, false) THEN 'Cash + Online'
                 WHEN COALESCE(m.has_cash, false) THEN 'Cash'
                 WHEN COALESCE(m.has_online, false) THEN 'Online'
                 ELSE 'Unavailable'
               END as payment_method
        FROM item_totals t
        LEFT JOIN item_payment_modes m ON m.name = t.name
        ORDER BY t.qty DESC
      `, [fromMs, toMs]),
      db.query(`
        WITH deduped_order_line AS (
          SELECT DISTINCT ON (
            order_id, item_id, name, quantity, unit_price_cents,
            COALESCE(modifiers::text, ''), COALESCE(notes, ''),
            COALESCE(discount_category, ''), COALESCE(discount_cents, 0)
          ) *
          FROM order_line
          ORDER BY
            order_id, item_id, name, quantity, unit_price_cents,
            COALESCE(modifiers::text, ''), COALESCE(notes, ''),
            COALESCE(discount_category, ''), COALESCE(discount_cents, 0),
            device_id, id
        ),
        deduped_payment AS (
          SELECT DISTINCT ON (
            order_id, method, COALESCE(payment_category, ''), amount_cents,
            COALESCE(amount_tendered_cents, amount_cents), COALESCE(change_cents, 0), created_at
          ) *
          FROM payment
          ORDER BY
            order_id, method, COALESCE(payment_category, ''), amount_cents,
            COALESCE(amount_tendered_cents, amount_cents), COALESCE(change_cents, 0), created_at,
            device_id, id
        ),
        order_items AS (
          SELECT order_id,
                 STRING_AGG(quantity || 'x ' || name, ', ' ORDER BY name) as items
          FROM deduped_order_line
          GROUP BY order_id
        ),
        order_payment_modes AS (
          SELECT order_id,
                 BOOL_OR(UPPER(COALESCE(payment_category, '')) = 'CASH'
                   OR (COALESCE(payment_category, '') = '' AND LOWER(method) = 'cash')) as has_cash,
                 BOOL_OR(UPPER(COALESCE(payment_category, '')) = 'ONLINE'
                   OR (COALESCE(payment_category, '') = '' AND LOWER(method) IN ('online', 'gcash'))) as has_online
          FROM deduped_payment
          GROUP BY order_id
        )
        SELECT o.id,
               o.created_at,
               COALESCE(e.name, '-') as employee_name,
               COALESCE(NULLIF(o.customer_name, ''), '-') as customer_name,
               CASE
                 WHEN COALESCE(pm.has_cash, false) AND COALESCE(pm.has_online, false) THEN 'Cash + Online'
                 WHEN COALESCE(pm.has_cash, false) THEN 'Cash'
                 WHEN COALESCE(pm.has_online, false) THEN 'Online'
                 ELSE 'Unavailable'
               END as payment_method,
               COALESCE(oi.items, '-') as items,
               o.total_cents
        FROM pos_order o
        LEFT JOIN employee e ON e.id = o.employee_id
        LEFT JOIN order_items oi ON oi.order_id = o.id
        LEFT JOIN order_payment_modes pm ON pm.order_id = o.id
        WHERE o.created_at >= $1 AND o.created_at < $2 AND o.status = 'paid'
          AND ${nonComplimentaryOrderPredicate('o')}
          AND NOT EXISTS (
            SELECT 1 FROM hidden_activity_history h
            WHERE h.shift_device_id = o.shift_device_id AND h.shift_id = o.shift_id::text
          )
        ORDER BY o.created_at DESC, o.id DESC
      `, [fromMs, toMs]),
      db.query(`
        WITH deduped_payment AS (
          SELECT DISTINCT ON (
            order_id, method, COALESCE(payment_category, ''), amount_cents,
            COALESCE(amount_tendered_cents, amount_cents), COALESCE(change_cents, 0), created_at
          ) *
          FROM payment
          ORDER BY
            order_id, method, COALESCE(payment_category, ''), amount_cents,
            COALESCE(amount_tendered_cents, amount_cents), COALESCE(change_cents, 0), created_at,
            device_id, id
        )
        SELECT method, payment_category, SUM(amount_cents) as total
        FROM deduped_payment p
        JOIN pos_order o ON o.id = p.order_id
        WHERE o.created_at >= $1 AND o.created_at < $2 AND o.status = 'paid'
          AND NOT EXISTS (
            SELECT 1 FROM hidden_activity_history h
            WHERE h.shift_device_id = o.shift_device_id AND h.shift_id = o.shift_id::text
          )
        GROUP BY method, payment_category
        ORDER BY method
      `, [fromMs, toMs]),
      db.query(`
        WITH deduped_payment AS (
          SELECT DISTINCT ON (
            order_id, method, COALESCE(payment_category, ''), amount_cents,
            COALESCE(amount_tendered_cents, amount_cents), COALESCE(change_cents, 0), created_at
          ) *
          FROM payment
          ORDER BY
            order_id, method, COALESCE(payment_category, ''), amount_cents,
            COALESCE(amount_tendered_cents, amount_cents), COALESCE(change_cents, 0), created_at,
            device_id, id
        )
        SELECT s.device_id, s.id, s.opened_at, s.starting_cash_cents, s.ending_cash_cents,
               s.cash_added_cents, s.cash_removed_cents,
               COALESCE(SUM(CASE
                 WHEN UPPER(COALESCE(p.payment_category, '')) = 'CASH'
                   OR (COALESCE(p.payment_category, '') = '' AND LOWER(p.method) = 'cash')
                 THEN p.amount_cents ELSE 0 END), 0) as cash_sales
        FROM shift s
        LEFT JOIN pos_order o ON o.shift_id::text = s.id::text
          AND o.shift_device_id = s.device_id
          AND o.status = 'paid'
          AND o.created_at >= $1 AND o.created_at < $2
          AND NOT EXISTS (
            SELECT 1 FROM hidden_activity_history h
            WHERE h.shift_device_id = o.shift_device_id AND h.shift_id = o.shift_id::text
          )
        LEFT JOIN deduped_payment p ON p.order_id = o.id
        WHERE s.opened_at >= $1 AND s.opened_at < $2
          AND NOT EXISTS (
            SELECT 1 FROM hidden_activity_history h
            WHERE h.shift_device_id = s.device_id AND h.shift_id = s.id::text
          )
        GROUP BY s.device_id, s.id, s.opened_at, s.starting_cash_cents, s.ending_cash_cents,
                 s.cash_added_cents, s.cash_removed_cents, s.opened_at
        ORDER BY s.opened_at
      `, [fromMs, toMs]),
      db.query(`
        SELECT COALESCE(discount_category, 'Discount') AS name,
               COALESCE(discount_scope, 'item') AS scope,
               COUNT(*) AS order_count,
               COALESCE(SUM(discount_cents), 0) AS amount_cents
        FROM pos_order o
        WHERE o.created_at >= $1 AND o.created_at < $2 AND o.status = 'paid' AND o.discount_cents > 0
          AND ${nonComplimentaryOrderPredicate('o')}
          AND NOT EXISTS (
            SELECT 1 FROM hidden_activity_history h
            WHERE h.shift_device_id = o.shift_device_id AND h.shift_id = o.shift_id::text
          )
        GROUP BY COALESCE(discount_category, 'Discount'), COALESCE(discount_scope, 'item')
        ORDER BY amount_cents DESC
      `, [fromMs, toMs])
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
      const hasCashSales = shiftCashSales > 0;
      if (!hasCashSales) {
        totals.latestNoCashStarting = starting;
        return totals;
      }
      totals.hasCashSales = true;
      const displayedStarting = starting + added - removed;
      const expected = displayedStarting + shiftCashSales;
      if (starting > 0 || hasCashSales) totals.hasActivity = true;
      totals.startingCash += displayedStarting;
      totals.expectedCashEnding += expected;
      totals.actualCashEnding += expected;
      totals.cashAdded += added;
      totals.cashRemoved += removed;
      return totals;
    }, { hasActivity: false, hasCashSales: false, latestNoCashStarting: 0, startingCash: 0, expectedCashEnding: 0, actualCashEnding: 0, cashAdded: 0, cashRemoved: 0 });

    if (!cashDrawer.hasCashSales && cashDrawer.latestNoCashStarting > 0) {
      cashDrawer.hasActivity = true;
      cashDrawer.startingCash = cashDrawer.latestNoCashStarting;
      cashDrawer.expectedCashEnding = cashDrawer.latestNoCashStarting;
      cashDrawer.actualCashEnding = cashDrawer.latestNoCashStarting;
    }

    cashDrawer.onlinePayments = onlinePayments;
    cashDrawer.totalCashAndOnline = cashDrawer.expectedCashEnding + onlinePayments;
    cashDrawer.difference = 0;
    cashDrawer.cashSales = cashSales;
    if (!cashDrawer.hasActivity) {
      cashDrawer.startingCash = 0;
      cashDrawer.expectedCashEnding = 0;
      cashDrawer.actualCashEnding = 0;
      cashDrawer.cashAdded = 0;
      cashDrawer.cashRemoved = 0;
      cashDrawer.onlinePayments = 0;
      cashDrawer.totalCashAndOnline = 0;
      cashDrawer.cashSales = 0;
    }
    delete cashDrawer.hasCashSales;
    delete cashDrawer.latestNoCashStarting;

    res.json({
      days,
      ordersToday: parseInt(summaryRes.rows[0].count),
      revenueToday: parseInt(summaryRes.rows[0].net),
      grossSales: parseInt(summaryRes.rows[0].gross),
      netSales: parseInt(summaryRes.rows[0].net),
      topItems: topItemsRes.rows,
      orderSummary: orderSummaryRes.rows,
      discountBreakdown: discountRes.rows,
      paymentBreakdown: payments,
      cashDrawer,
      reportWindow: range
    });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// GET /admin/sales?days=7&fromDate=YYYY-MM-DD&toDate=YYYY-MM-DD — sales chart data
app.get('/admin/sales', adminAuthenticate, async (req, res) => {
  try {
    const range = await reportRange(req.query.days || 7, req.query.fromDate, req.query.toDate);
    const { fromMs, toMs, cutoffMinutes } = range;
    await ensureHiddenActivityHistoryTable();
    const result = await db.query(`
      SELECT
        TO_CHAR(TO_TIMESTAMP((o.created_at - $3) / 1000) AT TIME ZONE 'Asia/Manila', 'YYYY-MM-DD') as date,
        COUNT(*) as orders,
        COALESCE(SUM(o.total_cents), 0) as revenue
      FROM pos_order o
      WHERE o.created_at >= $1 AND o.created_at < $2 AND o.status = 'paid'
        AND ${nonComplimentaryOrderPredicate('o')}
        AND NOT EXISTS (
          SELECT 1 FROM hidden_activity_history h
          WHERE h.shift_device_id = o.shift_device_id AND h.shift_id = o.shift_id::text
        )
      GROUP BY date ORDER BY date
    `, [fromMs, toMs, cutoffMinutes * 60 * 1000]);
    res.json({ rows: result.rows, reportWindow: range });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// GET /admin/orders/:id — single order with line items and payments
app.get('/admin/orders/:id', adminAuthenticate, async (req, res) => {
  try {
    const orderRes = await db.query(`
      SELECT o.*, e.name as employee_name
      FROM pos_order o
      LEFT JOIN employee e ON e.id = o.employee_id
      WHERE o.id = $1
      LIMIT 1
    `, [req.params.id]);
    const order = orderRes.rows[0];
    if (!order) return res.status(404).json({ error: 'Order not found' });

    const linesRes = await db.query(`
      WITH deduped_order_line AS (
        SELECT DISTINCT ON (
          order_id, item_id, name, quantity, unit_price_cents,
          COALESCE(modifiers::text, '[]'), COALESCE(notes, ''),
          COALESCE(discount_category, ''), discount_cents
        )
          device_id, id, order_id, item_id, name, quantity, unit_price_cents,
          modifiers, notes, discount_category, discount_cents
        FROM order_line
        WHERE order_id = $1
        ORDER BY order_id, item_id, name, quantity, unit_price_cents,
          COALESCE(modifiers::text, '[]'), COALESCE(notes, ''),
          COALESCE(discount_category, ''), discount_cents, device_id, id
      )
      SELECT *, (quantity * unit_price_cents) - discount_cents AS line_total_cents
      FROM deduped_order_line
      ORDER BY name, id
    `, [req.params.id]);

    const paymentsRes = await db.query(`
      WITH deduped_payment AS (
        SELECT DISTINCT ON (
          order_id, method, COALESCE(payment_category, ''), amount_cents,
          COALESCE(amount_tendered_cents, -1), COALESCE(change_cents, -1), created_at
        )
          device_id, id, order_id, method, amount_cents, amount_tendered_cents,
          change_cents, created_at, payment_category
        FROM payment
        WHERE order_id = $1
        ORDER BY order_id, method, COALESCE(payment_category, ''), amount_cents,
          COALESCE(amount_tendered_cents, -1), COALESCE(change_cents, -1), created_at,
          device_id, id
      )
      SELECT * FROM deduped_payment
      ORDER BY created_at, method
    `, [req.params.id]);

    const payments = paymentsRes.rows.map(row => ({
      ...row,
      payment_category: paymentCategory(row) || row.payment_category || null
    }));
    const paymentCategories = [...new Set(payments.map(p => p.payment_category).filter(Boolean))];

    res.json({ order, lines: linesRes.rows, payments, payment_categories: paymentCategories });
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

// GET /admin/inventory?fromDate=YYYY-MM-DD&toDate=YYYY-MM-DD — stock levels with low-stock flag
app.get('/admin/inventory', adminAuthenticate, async (req, res) => {
  try {
    let dateRange = null;
    if (req.query.fromDate && req.query.toDate) {
      const range = await reportRange(null, req.query.fromDate, req.query.toDate);
      dateRange = { fromMs: range.fromMs, toMs: range.toMs };
    }
    res.json(await inventory.list(dateRange));
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

// ─── Happenings / Audit History Endpoint ──────────────────────────────────────
async function handleGetHappenings(req, res) {
  try {
    await ensureHiddenActivityHistoryTable();
    const { start, end, fromDate, toDate } = req.query;

    // All timestamp columns here are bigint (epoch ms)
    let startMs = start ? new Date(start).getTime() : null;
    let endMs   = end   ? new Date(end).getTime()   : null;
    if (fromDate && toDate) {
      const range = await reportRange(null, fromDate, toDate);
      startMs = range.fromMs;
      endMs = range.toMs;
    }

    // --- inventory: pull from sync_inventory_event (where stock changes are actually recorded)
    // Only show manual restocks/adjustments — exclude "Order sale:" deductions
    let invRows = [];
    if (startMs !== null && endMs !== null) {
      const r = await db.query(
        `SELECT sie.event_id, sie.ingredient_id, sie.delta_quantity, sie.reason, sie.created_at,
                i.name AS ingredient_name
         FROM sync_inventory_event sie
         LEFT JOIN ingredient i ON i.id = sie.ingredient_id
         WHERE sie.created_at >= $1 AND sie.created_at < $2
           AND sie.reason NOT LIKE 'Order sale:%'
         ORDER BY sie.created_at DESC LIMIT 300`,
        [startMs, endMs]
      ).catch(() => ({ rows: [] }));
      invRows = r.rows || [];
    } else {
      const r = await db.query(
        `SELECT sie.event_id, sie.ingredient_id, sie.delta_quantity, sie.reason, sie.created_at,
                i.name AS ingredient_name
         FROM sync_inventory_event sie
         LEFT JOIN ingredient i ON i.id = sie.ingredient_id
         WHERE sie.reason NOT LIKE 'Order sale:%'
         ORDER BY sie.created_at DESC LIMIT 300`
      ).catch(() => ({ rows: [] }));
      invRows = r.rows || [];
    }

    // --- shifts: bigint epoch ms columns (opened_at / closed_at)
    let shiftRows = [];
    if (startMs !== null && endMs !== null) {
      const r = await db.query(
        `SELECT device_id, id, opened_at, closed_at, employee_id, starting_cash_cents, ending_cash_cents
         FROM shift
         WHERE (opened_at >= $1 AND opened_at < $2) OR (closed_at IS NOT NULL AND closed_at >= $1 AND closed_at < $2)
         ORDER BY opened_at DESC LIMIT 100`,
        [startMs, endMs]
      ).catch(() => ({ rows: [] }));
      shiftRows = r.rows || [];
    } else {
      const r = await db.query(
        `SELECT device_id, id, opened_at, closed_at, employee_id, starting_cash_cents, ending_cash_cents
         FROM shift ORDER BY opened_at DESC LIMIT 100`
      ).catch(() => ({ rows: [] }));
      shiftRows = r.rows || [];
    }

    const list = [];

    // --- inventory rows from sync_inventory_event
    invRows.forEach(row => {
      const delta = parseFloat(row.delta_quantity) || 0;
      const isRestock = delta > 0;
      const ingName = row.ingredient_name || row.ingredient_id;
      list.push({
        id: 'inv-' + row.event_id,
        eventType: isRestock ? 'INVENTORY_RESTOCK' : 'INVENTORY_ADJUSTMENT',
        category: 'Inventory',
        title: isRestock ? 'Stock Restocked' : 'Stock Adjusted',
        description: `${ingName}: ${isRestock ? '+' : ''}${delta} — ${row.reason || 'Manual adjustment'}`,
        actorName: row.device_id || 'Staff',
        amountCents: null,
        deltaQty: delta,
        timestamp: parseInt(row.created_at, 10)
      });
    });

    // --- shift rows
    shiftRows.forEach(row => {
      const shiftDeviceId = String(row.device_id || '');
      const shiftId = String(row.id || '');
      if (row.opened_at) {
        list.push({
          id: `shift-open-${shiftDeviceId}|${shiftId}`,
          eventType: 'SHIFT_OPENED',
          category: 'Shifts',
          title: 'Shift Opened',
          description: `Shift #${row.id} opened with ₱${((row.starting_cash_cents || 0) / 100).toFixed(2)} starting cash`,
          actorName: row.employee_id || 'Staff',
          amountCents: row.starting_cash_cents,
          timestamp: parseInt(row.opened_at, 10)
        });
      }
      if (row.closed_at) {
        list.push({
          id: `shift-close-${shiftDeviceId}|${shiftId}`,
          eventType: 'SHIFT_CLOSED',
          category: 'Shifts',
          title: 'Shift Closed',
          description: `Shift #${row.id} closed with ₱${((row.ending_cash_cents || 0) / 100).toFixed(2)} ending cash`,
          actorName: row.employee_id || 'Staff',
          amountCents: row.ending_cash_cents,
          timestamp: parseInt(row.closed_at, 10)
        });
      }
    });

    const hiddenRows = await db.query('SELECT event_id FROM hidden_activity_history')
      .catch(() => ({ rows: [] }));
    const hiddenIds = new Set((hiddenRows.rows || []).map(row => row.event_id));
    const visibleList = list.filter(item => !(hiddenIds.has(item.id) && HIDEABLE_HAPPENING_ID_PATTERN.test(item.id)));
    visibleList.sort((a, b) => b.timestamp - a.timestamp);
    res.json(visibleList);
  } catch (err) {
    console.error('GET happenings error:', err);
    res.status(500).json({ error: 'Internal server error' });
  }
}

async function ensureHiddenActivityHistoryTable() {
  await db.query(`
    CREATE TABLE IF NOT EXISTS hidden_activity_history (
      event_id TEXT PRIMARY KEY,
      hidden_by TEXT NOT NULL DEFAULT 'admin',
      hidden_at BIGINT NOT NULL,
      shift_device_id TEXT,
      shift_id TEXT,
      event_type TEXT
    )
  `);
  await db.query('ALTER TABLE hidden_activity_history ADD COLUMN IF NOT EXISTS shift_device_id TEXT');
  await db.query('ALTER TABLE hidden_activity_history ADD COLUMN IF NOT EXISTS shift_id TEXT');
  await db.query('ALTER TABLE hidden_activity_history ADD COLUMN IF NOT EXISTS event_type TEXT');
}

app.get('/admin/happenings', adminAuthenticate, handleGetHappenings);
app.delete('/admin/happenings/:id', adminAuthenticate, async (req, res) => {
  try {
    const eventId = String(req.params.id || '');
    const match = eventId.match(HIDEABLE_HAPPENING_ID_PATTERN);
    if (!match) {
      return res.status(400).json({ error: 'Only shift activity history entries can be deleted.' });
    }
    const eventType = match[1] === 'open' ? 'SHIFT_OPENED' : 'SHIFT_CLOSED';
    const shiftDeviceId = match[2];
    const shiftId = match[3];
    await ensureHiddenActivityHistoryTable();
    await db.query(
      `INSERT INTO hidden_activity_history(event_id, hidden_by, hidden_at, shift_device_id, shift_id, event_type)
       VALUES ($1, $2, $3, $4, $5, $6)
       ON CONFLICT (event_id) DO UPDATE
       SET hidden_by = EXCLUDED.hidden_by,
           hidden_at = EXCLUDED.hidden_at,
           shift_device_id = EXCLUDED.shift_device_id,
           shift_id = EXCLUDED.shift_id,
           event_type = EXCLUDED.event_type`,
      [eventId, req.admin?.username || 'admin', Date.now(), shiftDeviceId, shiftId, eventType]
    );
    res.status(204).end();
  } catch (err) {
    console.error('DELETE happening error:', err);
    res.status(500).json({ error: 'Internal server error' });
  }
});
app.get('/rest/v1/happenings', (req, res, next) => {
  adminAuthenticate(req, res, (err) => {
    if (!err && req.admin) return handleGetHappenings(req, res);
    authenticate(req, res, () => handleGetHappenings(req, res));
  });
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
