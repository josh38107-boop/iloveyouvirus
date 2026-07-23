const crypto = require('crypto');
const express = require('express');

const API_VERSION = '1.0.0';
const BRANCH_ID = process.env.DEFAULT_BRANCH_ID || 'main';
const SESSION_COOKIE = 'kape_admin_session';
const LEGACY_ENABLED = String(process.env.LEGACY_SYNC_ENABLED || 'true').toLowerCase() === 'true';
const TOKEN_PEPPER = process.env.TOKEN_PEPPER || process.env.API_KEY || 'change-me';
const SESSION_SECRET = process.env.SESSION_SECRET || process.env.API_KEY || 'change-me';
const API_KEY = process.env.API_KEY || 'changeme';

const TABLES = {
  menu_category: { role: 'manager', keys: ['id'], columns: ['id', 'name', 'sort_order'] },
  menu_item: { role: 'manager', keys: ['id'], columns: ['id', 'category_id', 'name', 'description', 'base_price_cents', 'active', 'complementary_exclusions'] },
  modifier_group: { role: 'manager', keys: ['id'], columns: ['id', 'name', 'required', 'max_selections'] },
  modifier_option: { role: 'manager', keys: ['id'], columns: ['id', 'group_id', 'name', 'price_delta_cents'] },
  menu_item_modifier_group: { role: 'manager', keys: ['item_id', 'group_id'], columns: ['item_id', 'group_id'] },
  ingredient: { role: 'manager', keys: ['id'], columns: ['id', 'name', 'unit', 'quantity_on_hand', 'low_stock_threshold', 'takeout_only'] },
  recipe_ingredient: { role: 'manager', keys: ['item_id', 'ingredient_id'], columns: ['item_id', 'ingredient_id', 'quantity_used'] },
  modifier_recipe_ingredient: { role: 'manager', keys: ['option_id', 'ingredient_id'], columns: ['option_id', 'ingredient_id', 'quantity_used', 'replaces_ingredient_id'] },
  payment_method: { role: 'manager', keys: ['id'], columns: ['id', 'name', 'enabled', 'is_system', 'payment_category', 'created_at', 'updated_at'] },
  discount_rule: { role: 'manager', keys: ['id'], columns: ['id', 'name', 'percent', 'scope', 'requires_reference', 'active', 'sort_order', 'created_at', 'updated_at'] },
  employee: { role: 'manager', keys: ['id'], columns: ['id', 'name', 'pin', 'role', 'active'] },
  store_settings: { role: 'manager', keys: ['id'], columns: ['id', 'store_name', 'tax_rate_percent', 'tip_presets', 'receipt_footer', 'senior_discount_percent', 'pwd_discount_percent', 'discount_settings_updated_at', 'void_refund_pin', 'payment_void_settings_updated_at'] },
  sync_device_authority: { role: 'manager', keys: ['branch_id'], columns: ['branch_id', 'manager_device_id', 'manager_device_name', 'revision', 'updated_at'] },
  sync_tombstone: { role: 'manager', keys: ['branch_id', 'entity_type', 'entity_id'], columns: ['branch_id', 'entity_type', 'entity_id', 'deleted_by_device', 'deleted_at'] },
  shift: { role: 'counter', keys: ['device_id', 'id'], columns: ['device_id', 'id', 'employee_id', 'opened_at', 'closed_at', 'starting_cash_cents', 'ending_cash_cents', 'cash_added_cents', 'cash_removed_cents'] },
  pos_order: { role: 'counter', keys: ['id'], columns: ['id', 'status', 'employee_id', 'shift_id', 'shift_device_id', 'subtotal_cents', 'discount_cents', 'discount_rule_id', 'discount_category', 'discount_percent', 'discount_scope', 'discount_reference', 'tax_cents', 'tip_cents', 'total_cents', 'created_at', 'paid_at', 'void_reason', 'customer_name', 'table_number', 'order_type'], immutable: true },
  order_line: { role: 'counter', keys: ['device_id', 'id'], columns: ['device_id', 'id', 'order_id', 'item_id', 'name', 'quantity', 'unit_price_cents', 'modifiers', 'notes', 'discount_category', 'discount_cents'] },
  payment: { role: 'counter', keys: ['device_id', 'id'], columns: ['device_id', 'id', 'order_id', 'method', 'amount_cents', 'amount_tendered_cents', 'change_cents', 'created_at', 'payment_category'] },
  receipt: { role: 'counter', keys: ['order_id'], columns: ['order_id', 'receipt_number', 'text', 'created_at'] },
  stock_snapshot: { role: 'counter', keys: ['device_id', 'shift_id', 'ingredient_id'], columns: ['device_id', 'shift_id', 'ingredient_id', 'quantity'] },
  order_inventory_add_on: { role: 'counter', keys: ['id'], columns: ['id', 'order_id', 'ingredient_id', 'quantity', 'created_at', 'restored_at', 'updated_at'] }
};

const CATALOG_TABLES = Object.entries(TABLES).filter(([, config]) => config.role === 'manager').map(([name]) => name);
const OPERATION_TABLES = ['shift', 'pos_order', 'order_line', 'payment', 'receipt', 'stock_snapshot', 'order_inventory_add_on'];
const READ_TABLES = { ...TABLES, inventory_balance: { columns: ['branch_id', 'ingredient_id', 'quantity'] } };

function now() { return Date.now(); }
function id() { return crypto.randomUUID(); }
function randomToken(bytes = 32) { return crypto.randomBytes(bytes).toString('base64url'); }
function digest(value) { return crypto.createHash('sha256').update(`${TOKEN_PEPPER}:${value}`).digest('hex'); }
function b64(value) { return Buffer.from(value).toString('base64url'); }
function unb64(value) { return Buffer.from(value, 'base64url').toString(); }
function constantEqual(a, b) {
  const aa = Buffer.from(String(a));
  const bb = Buffer.from(String(b));
  return aa.length === bb.length && crypto.timingSafeEqual(aa, bb);
}

function parseCookies(req) {
  return Object.fromEntries(String(req.headers.cookie || '').split(';').map(part => part.trim()).filter(part => part.includes('=')).map(part => {
    const index = part.indexOf('=');
    return [decodeURIComponent(part.slice(0, index)), decodeURIComponent(part.slice(index + 1))];
  }));
}

function makeAdminSession(username) {
  const payload = b64(JSON.stringify({ sub: username, exp: now() + 8 * 60 * 60 * 1000 }));
  const signature = crypto.createHmac('sha256', SESSION_SECRET).update(payload).digest('base64url');
  return `${payload}.${signature}`;
}

function verifyAdminSession(token) {
  try {
    const [payload, signature] = String(token || '').split('.');
    const expected = crypto.createHmac('sha256', SESSION_SECRET).update(payload).digest('base64url');
    if (!constantEqual(signature, expected)) return null;
    const session = JSON.parse(unb64(payload));
    return session.exp > now() ? session : null;
  } catch { return null; }
}

function sessionCookie(value, clear = false) {
  const secure = process.env.NODE_ENV === 'production' ? '; Secure' : '';
  return `${SESSION_COOKIE}=${clear ? '' : encodeURIComponent(value)}; Path=/; HttpOnly; SameSite=Strict${secure}; Max-Age=${clear ? 0 : 28800}`;
}

function createRateLimiter({ windowMs, max }) {
  const attempts = new Map();
  return (req, res, next) => {
    const key = req.ip || req.socket.remoteAddress || 'unknown';
    const cutoff = now() - windowMs;
    const entries = (attempts.get(key) || []).filter(time => time > cutoff);
    if (entries.length >= max) return res.status(429).json({ error: 'Too many attempts. Try again later.' });
    entries.push(now());
    attempts.set(key, entries);
    next();
  };
}

function createCloud(db) {
  async function findDeviceToken(token) {
    if (!token) return null;
    const result = await db.query(`
      SELECT id, branch_id, hardware_id, name, role, status
      FROM sync_device WHERE token_hash = $1 AND status = 'active' LIMIT 1
    `, [digest(token)]);
    return result.rows[0] || null;
  }

  function bearer(req) {
    const authorization = String(req.headers.authorization || '');
    return authorization.startsWith('Bearer ') ? authorization.slice(7).trim() : String(req.headers.apikey || '');
  }

  async function deviceAuth(req, res, next) {
    try {
      const device = await findDeviceToken(bearer(req));
      if (!device) return res.status(401).json({ error: 'Invalid or revoked device token' });
      req.syncDevice = device;
      await db.query('UPDATE sync_device SET last_seen_at = $1 WHERE id = $2', [now(), device.id]);
      next();
    } catch (error) { next(error); }
  }

  function adminAuth(req, res, next) {
    const session = verifyAdminSession(parseCookies(req)[SESSION_COOKIE]);
    if (session) { req.admin = session; return next(); }
    if (LEGACY_ENABLED && constantEqual(bearer(req), API_KEY)) { req.admin = { sub: 'legacy-admin' }; return next(); }
    return res.status(401).json({ error: 'Admin authentication required' });
  }

  async function legacyAuth(req, res, next) {
    if (!LEGACY_ENABLED) return res.status(410).json({ error: 'Legacy synchronization is disabled' });
    if (constantEqual(bearer(req), API_KEY)) return next();
    return deviceAuth(req, res, next);
  }

  const configuredOrigins = String(process.env.ALLOWED_ORIGINS || process.env.RENDER_EXTERNAL_URL || '')
    .split(',').map(value => value.trim().replace(/\/$/, '')).filter(Boolean);
  const corsOptions = {
    credentials: true,
    origin(origin, callback) {
      if (!origin || configuredOrigins.includes(origin.replace(/\/$/, '')) ||
          (process.env.NODE_ENV !== 'production' && /^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(origin))) {
        return callback(null, true);
      }
      callback(new Error('Origin not allowed'));
    }
  };

  function requireRole(device, required) {
    if (required === 'manager' && device.role !== 'manager') {
      const error = new Error('Manager device role required');
      error.status = 403;
      throw error;
    }
  }

  function cleanRow(config, data) {
    if (!data || typeof data !== 'object' || Array.isArray(data)) throw Object.assign(new Error('Operation data must be an object'), { status: 400 });
    const row = {};
    for (const column of config.columns) if (Object.prototype.hasOwnProperty.call(data, column)) row[column] = data[column];
    for (const key of config.keys) if (row[key] == null) throw Object.assign(new Error(`Missing required field '${key}'`), { status: 400 });
    return row;
  }

  async function upsert(client, entity, data) {
    const config = TABLES[entity];
    if (!config) throw Object.assign(new Error(`Unsupported entity '${entity}'`), { status: 400 });
    const record = cleanRow(config, data);
    const columns = Object.keys(record);
    const quoted = columns.map(column => `"${column}"`).join(', ');
    const values = columns.map((_, index) => `$${index + 1}`).join(', ');
    const conflicts = config.keys.map(key => `"${key}"`).join(', ');
    let updateColumns = columns.filter(column => !config.keys.includes(column));
    if (config.immutable) {
      updateColumns = updateColumns.filter(column => ['status', 'void_reason', 'paid_at'].includes(column));
      const current = await client.query('SELECT status FROM pos_order WHERE id=$1', [record.id]);
      if (current.rowCount && record.status && record.status !== current.rows[0].status) {
        const transitions = {
          open: ['paid', 'completed', 'void', 'cancelled'], pending: ['paid', 'completed', 'void', 'cancelled'],
          paid: ['refunded', 'void'], completed: ['refunded', 'void'], void: [], cancelled: [], refunded: []
        };
        if (!(transitions[current.rows[0].status] || []).includes(record.status)) {
          throw Object.assign(new Error(`Unsupported order transition ${current.rows[0].status} -> ${record.status}`), { status: 409 });
        }
      }
    }
    const update = updateColumns.length
      ? `DO UPDATE SET ${updateColumns.map(column => `"${column}" = EXCLUDED."${column}"`).join(', ')}`
      : 'DO NOTHING';
    const result = await client.query(`INSERT INTO "${entity}" (${quoted}) VALUES (${values}) ON CONFLICT (${conflicts}) ${update} RETURNING *`, columns.map(column => record[column]));
    if (result.rows[0]) return result.rows[0];
    const where = config.keys.map((key, index) => `"${key}" = $${index + 1}`).join(' AND ');
    return (await client.query(`SELECT * FROM "${entity}" WHERE ${where} LIMIT 1`, config.keys.map(key => record[key]))).rows[0];
  }

  async function applyInventoryEvent(client, device, data) {
    const eventId = String(data.event_id || data.p_event_id || '');
    const ingredientId = String(data.ingredient_id || data.p_ingredient_id || '');
    const delta = Number(data.delta_quantity ?? data.p_delta_quantity);
    const branchId = String(data.branch_id || data.p_branch_id || device.branch_id);
    if (!eventId || !ingredientId || !Number.isFinite(delta)) throw Object.assign(new Error('event_id, ingredient_id, and delta_quantity are required'), { status: 400 });
    const inserted = await client.query(`
      INSERT INTO sync_inventory_event(event_id, branch_id, device_id, ingredient_id, delta_quantity, reason, created_at)
      VALUES ($1,$2,$3,$4,$5,$6,$7) ON CONFLICT (event_id) DO NOTHING RETURNING event_id
    `, [eventId, branchId, device.id, ingredientId, delta, data.reason || data.p_reason || null, Number(data.created_at || data.p_created_at || now())]);
    if (inserted.rowCount) {
      await client.query(`
        INSERT INTO inventory_balance(branch_id, ingredient_id, quantity)
        VALUES ($1,$2,$3) ON CONFLICT (branch_id, ingredient_id)
        DO UPDATE SET quantity = inventory_balance.quantity + EXCLUDED.quantity
      `, [branchId, ingredientId, delta]);
    }
    return (await client.query('SELECT branch_id, ingredient_id, quantity FROM inventory_balance WHERE branch_id=$1 AND ingredient_id=$2', [branchId, ingredientId])).rows[0];
  }

  async function applyTombstone(client, data) {
    const entity = String(data.entity_type || '');
    const entityId = String(data.entity_id || '');
    const simple = { menu_category: 'id', menu_item: 'id', modifier_group: 'id', modifier_option: 'id', ingredient: 'id', payment_method: 'id' };
    const composite = {
      menu_item_modifier_group: ['item_id', 'group_id'], recipe_ingredient: ['item_id', 'ingredient_id'],
      modifier_recipe_ingredient: ['option_id', 'ingredient_id']
    };
    if (simple[entity]) await client.query(`DELETE FROM "${entity}" WHERE "${simple[entity]}"=$1`, [entityId]);
    else if (composite[entity]) {
      const parts = entityId.split('|');
      if (parts.length === 2) await client.query(`DELETE FROM "${entity}" WHERE "${composite[entity][0]}"=$1 AND "${composite[entity][1]}"=$2`, parts);
    }
  }

  async function addChange(client, device, entity, entityId, operation, payload) {
    return client.query(`INSERT INTO sync_change(branch_id, entity_type, entity_id, operation, payload, device_id, created_at)
      VALUES ($1,$2,$3,$4,$5,$6,$7) RETURNING sequence`,
      [device.branch_id, entity, entityId, operation, payload || null, device.id, now()]);
  }

  async function safeRows(sql, params = []) {
    try { return (await db.query(sql, params)).rows; } catch (error) {
      if (error.code === '42P01') return [];
      throw error;
    }
  }

  async function recordAdminChange(entity, record, operation = 'upsert') {
    const config = TABLES[entity];
    if (!config || !record) return;
    const entityId = config.keys.map(key => record[key]).join('|');
    await db.query(`INSERT INTO sync_change(branch_id, entity_type, entity_id, operation, payload, device_id, created_at)
      VALUES ($1,$2,$3,$4,$5,NULL,$6)`, [BRANCH_ID, entity, entityId, operation, operation === 'delete' ? null : record, now()]);
  }

  function attachRoutes(app) {
    const loginLimit = createRateLimiter({ windowMs: 15 * 60 * 1000, max: 10 });
    const enrollmentLimit = createRateLimiter({ windowMs: 10 * 60 * 1000, max: 20 });

    app.post('/admin/login', loginLimit, (req, res) => {
      const username = String(req.body?.username || '');
      const password = String(req.body?.password || '');
      const valid = constantEqual(username, process.env.ADMIN_USERNAME || 'admin') && constantEqual(password, process.env.ADMIN_PASSWORD || 'local-development-only');
      if (!valid) return res.status(401).json({ error: 'Invalid credentials' });
      res.setHeader('Set-Cookie', sessionCookie(makeAdminSession(username)));
      res.json({ success: true, user: username });
    });
    app.post('/admin/logout', (req, res) => { res.setHeader('Set-Cookie', sessionCookie('', true)); res.json({ success: true }); });
    app.get('/admin/session', adminAuth, (req, res) => res.json({ authenticated: true, user: req.admin.sub }));

    app.get('/admin/devices', adminAuth, async (req, res, next) => {
      try { res.json((await db.query(`SELECT id, branch_id, hardware_id, name, role, status, last_seen_at, created_at, revoked_at FROM sync_device ORDER BY created_at DESC`)).rows); }
      catch (error) { next(error); }
    });
    app.post('/admin/enrollments', adminAuth, async (req, res, next) => {
      try {
        const deviceName = String(req.body?.deviceName || '').trim();
        const role = req.body?.role === 'manager' ? 'manager' : 'counter';
        if (!deviceName || deviceName.length > 80) return res.status(400).json({ error: 'Device name is required and must be 80 characters or fewer' });
        const code = randomToken(6).toUpperCase();
        const expiresAt = now() + 10 * 60 * 1000;
        await db.query(`INSERT INTO sync_enrollment(id, code_hash, branch_id, device_name, role, expires_at, created_by, created_at)
          VALUES ($1,$2,$3,$4,$5,$6,$7,$8)`, [id(), digest(code), BRANCH_ID, deviceName, role, expiresAt, req.admin.sub, now()]);
        res.status(201).json({ code, deviceName, role, expiresAt });
      } catch (error) { next(error); }
    });
    app.post('/admin/devices/:id/revoke', adminAuth, async (req, res, next) => {
      try { await db.query(`UPDATE sync_device SET status='revoked', revoked_at=$1 WHERE id=$2`, [now(), req.params.id]); res.json({ success: true }); }
      catch (error) { next(error); }
    });
    app.post('/admin/devices/:id/reenroll', adminAuth, async (req, res, next) => {
      try {
        const found = await db.query('SELECT name, role, branch_id FROM sync_device WHERE id=$1', [req.params.id]);
        if (!found.rowCount) return res.status(404).json({ error: 'Device not found' });
        const device = found.rows[0], code = randomToken(6).toUpperCase(), expiresAt = now() + 10 * 60 * 1000;
        await db.query(`UPDATE sync_device SET status='revoked', revoked_at=$1 WHERE id=$2`, [now(), req.params.id]);
        await db.query(`INSERT INTO sync_enrollment(id, code_hash, branch_id, device_name, role, expires_at, created_by, created_at)
          VALUES ($1,$2,$3,$4,$5,$6,$7,$8)`, [id(), digest(code), device.branch_id, device.name, device.role, expiresAt, req.admin.sub, now()]);
        res.json({ code, deviceName: device.name, role: device.role, expiresAt });
      } catch (error) { next(error); }
    });

    app.post('/sync/v1/enroll', enrollmentLimit, async (req, res, next) => {
      let client;
      try {
        client = await db.pool.connect();
        const code = String(req.body?.code || '').trim().toUpperCase();
        const hardwareId = String(req.body?.hardwareId || req.body?.deviceId || '').trim();
        const requestedName = String(req.body?.deviceName || '').trim();
        if (!code || !hardwareId) return res.status(400).json({ error: 'Enrollment code and device ID are required' });
        await client.query('BEGIN');
        const enrollment = await client.query(`SELECT * FROM sync_enrollment WHERE code_hash=$1 FOR UPDATE`, [digest(code)]);
        const row = enrollment.rows[0];
        if (!row || row.used_at || Number(row.expires_at) <= now()) {
          await client.query('ROLLBACK');
          return res.status(400).json({ error: 'Enrollment code is invalid, expired, or already used' });
        }
        const token = randomToken(), tokenHash = digest(token), deviceId = id();
        const saved = await client.query(`
          INSERT INTO sync_device(id, branch_id, hardware_id, name, role, token_hash, status, last_seen_at, created_at, revoked_at)
          VALUES ($1,$2,$3,$4,$5,$6,'active',$7,$7,NULL)
          ON CONFLICT (branch_id, hardware_id) DO UPDATE SET name=EXCLUDED.name, role=EXCLUDED.role,
            token_hash=EXCLUDED.token_hash, status='active', last_seen_at=EXCLUDED.last_seen_at, revoked_at=NULL
          RETURNING id, branch_id, name, role
        `, [deviceId, row.branch_id, hardwareId, requestedName || row.device_name, row.role, tokenHash, now()]);
        await client.query('UPDATE sync_enrollment SET used_at=$1 WHERE id=$2', [now(), row.id]);
        await client.query('COMMIT');
        res.status(201).json({ token, device: saved.rows[0], serverVersion: API_VERSION });
      } catch (error) { if (client) await client.query('ROLLBACK').catch(() => {}); next(error); }
      finally { if (client) client.release(); }
    });

    app.get('/sync/v1/status', deviceAuth, async (req, res, next) => {
      try { await db.query('SELECT 1'); res.json({ ok: true, serverVersion: API_VERSION, device: req.syncDevice, serverTime: now() }); }
      catch (error) { next(error); }
    });
    app.get('/sync/v1/bootstrap', deviceAuth, async (req, res, next) => {
      try {
        const catalog = {};
        for (const table of CATALOG_TABLES.filter(name => !['sync_tombstone'].includes(name))) catalog[table] = await safeRows(`SELECT * FROM "${table}"`);
        const operations = {};
        const lookback = now() - 7 * 24 * 60 * 60 * 1000;
        operations.shift = await safeRows('SELECT * FROM shift WHERE opened_at >= $1', [lookback]);
        operations.pos_order = await safeRows('SELECT * FROM pos_order WHERE created_at >= $1', [lookback]);
        const cursor = Number((await db.query('SELECT COALESCE(MAX(sequence),0) AS cursor FROM sync_change WHERE branch_id=$1', [req.syncDevice.branch_id])).rows[0].cursor);
        res.json({ serverVersion: API_VERSION, cursor, device: req.syncDevice, catalog, operations,
          inventoryBalances: await safeRows('SELECT ingredient_id, quantity FROM inventory_balance WHERE branch_id=$1', [req.syncDevice.branch_id]),
          tombstones: await safeRows('SELECT * FROM sync_tombstone WHERE branch_id=$1', [req.syncDevice.branch_id]) });
      } catch (error) { next(error); }
    });
    app.get('/sync/v1/changes', deviceAuth, async (req, res, next) => {
      try {
        const cursor = Math.max(0, Number(req.query.cursor || 0));
        const result = await db.query(`SELECT sequence, entity_type, entity_id, operation, payload, created_at
          FROM sync_change WHERE branch_id=$1 AND sequence>$2 ORDER BY sequence LIMIT 500`, [req.syncDevice.branch_id, cursor]);
        const nextCursor = result.rows.length ? Number(result.rows[result.rows.length - 1].sequence) : cursor;
        res.json({ changes: result.rows, nextCursor, hasMore: result.rows.length === 500 });
      } catch (error) { next(error); }
    });
    app.get('/sync/v1/records/:entity', deviceAuth, async (req, res, next) => {
      try {
        const entity = String(req.params.entity || '');
        const config = READ_TABLES[entity];
        if (!config) return res.status(404).json({ error: 'Unsupported synchronization entity' });
        const allowed = new Set(config.columns);
        const selected = String(req.query.select || '*') === '*' ? '*' : String(req.query.select).split(',').map(value => value.trim());
        if (selected !== '*' && selected.some(column => !allowed.has(column))) return res.status(400).json({ error: 'Invalid selected column' });
        const clauses = [], values = [];
        for (const [column, raw] of Object.entries(req.query)) {
          if (['select', 'order', 'limit', 'offset'].includes(column)) continue;
          if (!allowed.has(column)) return res.status(400).json({ error: `Invalid filter column '${column}'` });
          const match = String(raw).match(/^(eq|neq|gte|gt|lte|lt)\.(.*)$/s);
          if (!match) return res.status(400).json({ error: `Invalid filter for '${column}'` });
          const operators = { eq: '=', neq: '!=', gte: '>=', gt: '>', lte: '<=', lt: '<' };
          values.push(match[2]); clauses.push(`"${column}" ${operators[match[1]]} $${values.length}`);
        }
        let order = '';
        if (req.query.order) {
          const [column, direction = 'asc'] = String(req.query.order).split('.');
          if (!allowed.has(column) || !['asc', 'desc'].includes(direction)) return res.status(400).json({ error: 'Invalid order' });
          order = `ORDER BY "${column}" ${direction.toUpperCase()}`;
        }
        const limit = Math.min(Math.max(Number(req.query.limit || 5000), 1), 5000);
        const columns = selected === '*' ? '*' : selected.map(column => `"${column}"`).join(',');
        const result = await db.query(`SELECT ${columns} FROM "${entity}" ${clauses.length ? `WHERE ${clauses.join(' AND ')}` : ''} ${order} LIMIT ${limit}`, values);
        res.json(result.rows);
      } catch (error) { next(error); }
    });
    app.post('/sync/v1/push', deviceAuth, async (req, res, next) => {
      const operations = Array.isArray(req.body?.operations) ? req.body.operations : [];
      if (!operations.length || operations.length > 100) return res.status(400).json({ error: 'Provide between 1 and 100 operations' });
      let client;
      try {
        client = await db.pool.connect();
        await client.query('BEGIN');
        const results = [];
        for (const operation of operations) {
          const mutationId = String(operation.mutationId || '');
          if (!mutationId) throw Object.assign(new Error('Every operation requires mutationId'), { status: 400 });
          const reserved = await client.query(`INSERT INTO sync_mutation(mutation_id, device_id, result, created_at)
            VALUES ($1,$2,$3,$4) ON CONFLICT DO NOTHING RETURNING mutation_id`, [mutationId, req.syncDevice.id, { processing: true }, now()]);
          if (!reserved.rowCount) {
            const previous = await client.query('SELECT result FROM sync_mutation WHERE mutation_id=$1 AND device_id=$2', [mutationId, req.syncDevice.id]);
            if (!previous.rowCount) throw Object.assign(new Error('Mutation ID belongs to another device'), { status: 409 });
            results.push({ mutationId, duplicate: true, ...previous.rows[0].result });
            continue;
          }
          let record, entity, operationType, entityId;
          if (operation.type === 'inventory_event') {
            entity = 'inventory_balance'; operationType = 'inventory_event';
            record = await applyInventoryEvent(client, req.syncDevice, operation.data || {});
            entityId = record.ingredient_id;
          } else if (operation.type === 'tombstone') {
            requireRole(req.syncDevice, 'manager');
            if (operation.data?.entity_type === 'payment_method') {
              throw Object.assign(new Error('Manage payment methods in the admin website.'), { status: 403 });
            }
            entity = 'sync_tombstone'; operationType = 'delete';
            record = await upsert(client, entity, { ...operation.data, branch_id: req.syncDevice.branch_id, deleted_by_device: req.syncDevice.hardware_id, deleted_at: operation.data?.deleted_at || now() });
            await applyTombstone(client, record); entityId = `${record.entity_type}:${record.entity_id}`;
          } else {
            entity = String(operation.entity || '');
            const config = TABLES[entity];
            if (!config) throw Object.assign(new Error(`Unsupported entity '${entity}'`), { status: 400 });
            requireRole(req.syncDevice, config.role);
            if (entity === 'payment_method') {
              throw Object.assign(new Error('Manage payment methods in the admin website.'), { status: 403 });
            }
            const operationData = entity === 'store_settings'
              ? Object.fromEntries(Object.entries(operation.data || {}).filter(([key]) =>
                  !['void_refund_pin', 'payment_void_settings_updated_at'].includes(key)))
              : operation.data;
            const candidateId = config.keys.map(key => operation.data?.[key]).join('|');
            const tombstone = config.role === 'manager' && entity !== 'sync_tombstone'
              ? await client.query('SELECT * FROM sync_tombstone WHERE branch_id=$1 AND entity_type=$2 AND entity_id=$3 LIMIT 1', [req.syncDevice.branch_id, entity, candidateId])
              : { rowCount: 0 };
            if (tombstone.rowCount) {
              record = tombstone.rows[0]; operationType = 'delete'; entityId = candidateId;
            } else {
              record = await upsert(client, entity, operationData);
              operationType = 'upsert'; entityId = config.keys.map(key => record[key]).join('|');
            }
          }
          const change = await addChange(client, req.syncDevice, entity, entityId, operationType, record);
          const result = { success: true, entity, entityId, sequence: Number(change.rows[0].sequence) };
          await client.query('UPDATE sync_mutation SET result=$1 WHERE mutation_id=$2', [result, mutationId]);
          results.push({ mutationId, ...result });
        }
        await client.query('COMMIT');
        res.json({ results });
      } catch (error) { if (client) await client.query('ROLLBACK').catch(() => {}); next(error); }
      finally { if (client) client.release(); }
    });
  }

  return { attachRoutes, adminAuth, deviceAuth, legacyAuth, corsOptions, recordAdminChange, legacyEnabled: LEGACY_ENABLED };
}

module.exports = {
  createCloud,
  TABLES,
  _test: { constantEqual, makeAdminSession, verifyAdminSession, parseCookies }
};
