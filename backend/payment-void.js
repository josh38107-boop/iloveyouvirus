const crypto = require('crypto');

function httpError(status, message) {
  return Object.assign(new Error(message), { status });
}

function validateExpectedUpdatedAt(value) {
  if (value == null || value === '') {
    throw httpError(400, 'Reload Payment & Void Settings before saving.');
  }
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < 0) {
    throw httpError(400, 'Reload Payment & Void Settings before saving.');
  }
  return parsed;
}

function validatePin(value) {
  const pin = String(value ?? '');
  if (!/^\d{4}$/.test(pin)) throw httpError(400, 'Authorization PIN must be exactly 4 digits.');
  return pin;
}

function validateMethodInput(input) {
  const name = String(input?.name || '').trim().replace(/\s+/g, ' ');
  if (!name) throw httpError(400, 'Enter a payment method name.');
  if (name.length > 60) throw httpError(400, 'Payment method name must be 60 characters or fewer.');
  if (['cash', 'online', 'gcash', 'split', 'complimentary'].includes(name.toLowerCase())) {
    throw httpError(400, 'That payment method name is reserved for a system method.');
  }
  const paymentCategory = String(input?.paymentCategory || '').toUpperCase();
  if (!['CASH', 'ONLINE'].includes(paymentCategory)) {
    throw httpError(400, 'Payment category must be Cash or Online.');
  }
  if (typeof input?.enabled !== 'boolean') {
    throw httpError(400, 'Enabled must be true or false.');
  }
  return { name, paymentCategory, enabled: input.enabled };
}

function normalizeId(value) {
  return String(value || '').toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
}

function publicMethod(row) {
  return {
    id: row.id,
    name: row.name,
    enabled: Boolean(row.enabled),
    isSystem: Boolean(row.is_system),
    paymentCategory: row.payment_category || null,
    createdAt: Number(row.created_at || 0),
    updatedAt: Number(row.updated_at || 0)
  };
}

function createPaymentVoidService(db, options = {}) {
  const branchId = options.branchId || 'main';
  const clock = options.now || Date.now;
  const randomId = options.randomId || (() => crypto.randomUUID().slice(0, 6));

  async function transaction(work) {
    const client = await db.pool.connect();
    try {
      await client.query('BEGIN');
      const result = await work(client);
      await client.query('COMMIT');
      return result;
    } catch (error) {
      await client.query('ROLLBACK').catch(() => {});
      throw error;
    } finally {
      client.release();
    }
  }

  async function recordChange(client, entityType, entityId, operation, payload) {
    await client.query(`INSERT INTO sync_change
      (branch_id, entity_type, entity_id, operation, payload, device_id, created_at)
      VALUES ($1,$2,$3,$4,$5,NULL,$6)`,
      [branchId, entityType, entityId, operation, operation === 'delete' ? null : payload, clock()]);
  }

  async function ensureUniqueName(client, name, excludedId = null) {
    const duplicate = await client.query(
      `SELECT 1 FROM payment_method
       WHERE LOWER(name)=LOWER($1) AND ($2::text IS NULL OR id<>$2)
       LIMIT 1`,
      [name, excludedId]
    );
    if (duplicate.rowCount) throw httpError(409, 'A payment method with that name already exists.');
  }

  async function getSettings(client = db) {
    const settings = await client.query(`SELECT void_refund_pin, payment_void_settings_updated_at
      FROM store_settings WHERE id='store' LIMIT 1`);
    if (!settings.rowCount) throw httpError(409, 'Store settings are not available.');
    const methods = await client.query(`SELECT * FROM payment_method
      ORDER BY is_system DESC, LOWER(name) ASC`);
    return {
      voidRefundPin: settings.rows[0].void_refund_pin || '1234',
      pinUpdatedAt: Number(settings.rows[0].payment_void_settings_updated_at || 0),
      paymentMethods: methods.rows.map(publicMethod)
    };
  }

  async function updatePin(input) {
    const pin = validatePin(input?.voidRefundPin);
    const expected = validateExpectedUpdatedAt(input?.expectedUpdatedAt);
    return transaction(async client => {
      const current = await client.query(`SELECT payment_void_settings_updated_at
        FROM store_settings WHERE id='store' FOR UPDATE`);
      if (!current.rowCount) throw httpError(409, 'Store settings are not available. Reload and try again.');
      if (Number(current.rows[0].payment_void_settings_updated_at) !== expected) {
        throw httpError(409, 'The authorization PIN changed on another screen. Reload before saving.');
      }
      const updatedAt = Math.max(clock(), expected + 1);
      const result = await client.query(`UPDATE store_settings SET
          void_refund_pin=$1, payment_void_settings_updated_at=$2
        WHERE id='store' RETURNING *`, [pin, updatedAt]);
      await recordChange(client, 'store_settings', 'store', 'upsert', result.rows[0]);
      return { voidRefundPin: pin, pinUpdatedAt: updatedAt };
    });
  }

  async function createMethod(input) {
    const value = validateMethodInput(input);
    return transaction(async client => {
      await client.query('LOCK TABLE payment_method IN SHARE ROW EXCLUSIVE MODE');
      await ensureUniqueName(client, value.name);
      const idBase = normalizeId(value.name) || 'payment';
      let id = idBase;
      if ((await client.query('SELECT 1 FROM payment_method WHERE id=$1', [id])).rowCount) {
        id = `${idBase}-${randomId()}`;
      }
      const timestamp = clock();
      const result = await client.query(`INSERT INTO payment_method
          (id,name,enabled,is_system,payment_category,created_at,updated_at)
        VALUES ($1,$2,$3,FALSE,$4,$5,$5) RETURNING *`,
        [id, value.name, value.enabled, value.paymentCategory, timestamp]);
      await recordChange(client, 'payment_method', id, 'upsert', result.rows[0]);
      return publicMethod(result.rows[0]);
    });
  }

  async function updateMethod(id, input) {
    const value = validateMethodInput(input);
    const expected = validateExpectedUpdatedAt(input?.expectedUpdatedAt);
    return transaction(async client => {
      await client.query('LOCK TABLE payment_method IN SHARE ROW EXCLUSIVE MODE');
      const current = await client.query('SELECT * FROM payment_method WHERE id=$1 FOR UPDATE', [id]);
      if (!current.rowCount) throw httpError(404, 'Payment method was not found.');
      if (current.rows[0].is_system) throw httpError(403, 'System payment methods cannot be changed.');
      if (Number(current.rows[0].updated_at || 0) !== expected) {
        throw httpError(409, 'This payment method changed on another screen. Reload before saving.');
      }
      await ensureUniqueName(client, value.name, id);
      const updatedAt = Math.max(clock(), expected + 1);
      const result = await client.query(`UPDATE payment_method SET
          name=$2, enabled=$3, payment_category=$4, updated_at=$5
        WHERE id=$1 RETURNING *`,
        [id, value.name, value.enabled, value.paymentCategory, updatedAt]);
      await recordChange(client, 'payment_method', id, 'upsert', result.rows[0]);
      return publicMethod(result.rows[0]);
    });
  }

  async function deleteMethod(id, input) {
    const expected = validateExpectedUpdatedAt(input?.expectedUpdatedAt);
    return transaction(async client => {
      const current = await client.query('SELECT * FROM payment_method WHERE id=$1 FOR UPDATE', [id]);
      if (!current.rowCount) throw httpError(404, 'Payment method was not found.');
      if (current.rows[0].is_system) throw httpError(403, 'System payment methods cannot be deleted.');
      if (Number(current.rows[0].updated_at || 0) !== expected) {
        throw httpError(409, 'This payment method changed on another screen. Reload before deleting.');
      }
      const deletedAt = clock();
      await client.query(`INSERT INTO sync_tombstone
          (branch_id,entity_type,entity_id,deleted_by_device,deleted_at)
        VALUES ($1,'payment_method',$2,'admin-dashboard',$3)
        ON CONFLICT (branch_id,entity_type,entity_id)
        DO UPDATE SET deleted_by_device='admin-dashboard', deleted_at=EXCLUDED.deleted_at`,
        [branchId, id, deletedAt]);
      await client.query('DELETE FROM payment_method WHERE id=$1', [id]);
      await recordChange(client, 'payment_method', id, 'delete', null);
      return { id, deleted: true };
    });
  }

  return { getSettings, updatePin, createMethod, updateMethod, deleteMethod };
}

module.exports = {
  createPaymentVoidService,
  validateExpectedUpdatedAt,
  validatePin,
  validateMethodInput,
  publicMethod
};
