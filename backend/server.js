require('dotenv').config();
const express = require('express');
const cors = require('cors');
const path = require('path');
const db = require('./db');
const rpc = require('./rpc');

const app = express();
const PORT = process.env.PORT || 3000;
const API_KEY = process.env.API_KEY || 'changeme';

// ─── Allowed tables (whitelist for security) ─────────────────────────────────
const ALLOWED_TABLES = new Set([
  'sync_device_authority', 'sync_tombstone',
  'menu_category', 'menu_item', 'modifier_group', 'modifier_option',
  'menu_item_modifier_group', 'ingredient', 'recipe_ingredient',
  'modifier_recipe_ingredient', 'payment_method', 'employee',
  'store_settings', 'shift', 'pos_order', 'order_line', 'payment',
  'receipt', 'stock_snapshot', 'inventory_balance', 'order_inventory_add_on'
]);

// ─── Middleware ───────────────────────────────────────────────────────────────
app.use(cors({ origin: '*' }));
app.use(express.json({ limit: '10mb' }));

// ─── Serve Admin Dashboard as static files ────────────────────────────────────
app.use(express.static(path.join(__dirname, '..', 'dashboard')));

// ─── Admin Login ──────────────────────────────────────────────────────────────
app.post('/admin/login', (req, res) => {
  const { username, password } = req.body || {};
  const ADMIN_USER = process.env.ADMIN_USERNAME || 'admin';
  const ADMIN_PASS = process.env.ADMIN_PASSWORD || 'KapeAdmin2024';
  if (username === ADMIN_USER && password === ADMIN_PASS) {
    return res.json({ token: API_KEY, success: true });
  }
  return res.status(401).json({ error: 'Invalid credentials' });
});
app.use(express.json({ limit: '10mb' }));

// Auth middleware — accepts apikey header or Authorization: Bearer <key>
function authenticate(req, res, next) {
  const apikey = req.headers['apikey'] || '';
  const bearer = (req.headers['authorization'] || '').replace('Bearer ', '');
  if (apikey === API_KEY || bearer === API_KEY) return next();
  return res.status(401).json({ error: 'Unauthorized' });
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
      if (result.rows.length) results.push(result.rows[0]);
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
    return res.json(result.rows);
  } catch (err) {
    console.error(`DELETE ${table}:`, err.message);
    return res.status(500).json({ error: err.message });
  }
}

// ─── PostgREST-compatible table routes ───────────────────────────────────────
app.all('/rest/v1/:table', authenticate, async (req, res) => {
  const { table } = req.params;
  if (!ALLOWED_TABLES.has(table)) {
    return res.status(404).json({ error: `Table '${table}' not found` });
  }
  switch (req.method) {
    case 'GET':    return handleGet(req, res, table);
    case 'POST':   return handlePost(req, res, table);
    case 'PATCH':  return handlePatch(req, res, table);
    case 'DELETE': return handleDelete(req, res, table);
    default: return res.status(405).json({ error: 'Method not allowed' });
  }
});

// ─── RPC routes ───────────────────────────────────────────────────────────────
app.post('/rest/v1/rpc/:fn', authenticate, async (req, res) => {
  const { fn } = req.params;
  const handler = rpc[fn];
  if (!handler) return res.status(404).json({ error: `RPC '${fn}' not found` });
  try {
    const result = await handler(req.body || {}, db);
    return res.json(result);
  } catch (err) {
    console.error(`RPC ${fn}:`, err.message);
    return res.status(500).json({ error: err.message });
  }
});

// ─── Admin Dashboard API routes ───────────────────────────────────────────────

// GET /admin/stats — today's summary stats
app.get('/admin/stats', authenticate, async (req, res) => {
  try {
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const todayMs = today.getTime();

    const [ordersRes, revenueRes, topItemsRes, paymentRes] = await Promise.all([
      db.query(`SELECT COUNT(*) as count FROM pos_order WHERE created_at >= $1 AND status != 'void'`, [todayMs]),
      db.query(`SELECT COALESCE(SUM(total_cents),0) as total FROM pos_order WHERE created_at >= $1 AND status != 'void'`, [todayMs]),
      db.query(`
        SELECT name, SUM(quantity) as qty
        FROM order_line ol
        JOIN pos_order o ON o.id = ol.order_id
        WHERE o.created_at >= $1 AND o.status != 'void'
        GROUP BY name ORDER BY qty DESC LIMIT 5
      `, [todayMs]),
      db.query(`
        SELECT method, SUM(amount_cents) as total
        FROM payment p
        JOIN pos_order o ON o.id = p.order_id
        WHERE o.created_at >= $1 AND o.status != 'void'
        GROUP BY method
      `, [todayMs])
    ]);

    res.json({
      ordersToday: parseInt(ordersRes.rows[0].count),
      revenueToday: parseInt(revenueRes.rows[0].total),
      topItems: topItemsRes.rows,
      paymentBreakdown: paymentRes.rows
    });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// GET /admin/sales?days=7 — sales chart data
app.get('/admin/sales', authenticate, async (req, res) => {
  try {
    const days = parseInt(req.query.days) || 7;
    const from = Date.now() - days * 24 * 60 * 60 * 1000;
    const result = await db.query(`
      SELECT
        TO_CHAR(TO_TIMESTAMP(created_at / 1000), 'YYYY-MM-DD') as date,
        COUNT(*) as orders,
        COALESCE(SUM(total_cents), 0) as revenue
      FROM pos_order
      WHERE created_at >= $1 AND status != 'void'
      GROUP BY date ORDER BY date
    `, [from]);
    res.json(result.rows);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// GET /admin/orders?limit=50&offset=0 — paginated orders
app.get('/admin/orders', authenticate, async (req, res) => {
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
app.get('/admin/inventory', authenticate, async (req, res) => {
  try {
    const result = await db.query(`
      SELECT id, name, unit, quantity_on_hand,
             low_stock_threshold,
             (quantity_on_hand <= low_stock_threshold) as low_stock
      FROM ingredient
      ORDER BY name
    `);
    res.json(result.rows);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// ─── Health check ─────────────────────────────────────────────────────────────
app.get('/health', (req, res) => res.json({ status: 'ok', timestamp: Date.now() }));

// ─── Start server ─────────────────────────────────────────────────────────────
app.listen(PORT, () => {
  console.log(`✅ Kanlungan Coffee Garage API running on port ${PORT}`);
});
