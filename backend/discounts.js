const crypto = require('crypto');

function httpError(status, message) {
  return Object.assign(new Error(message), { status });
}

function normalizeId(value) {
  return String(value || '').toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
}

function validatePercent(value, label = 'Discount') {
  const percent = Number(value);
  if (!Number.isFinite(percent) || percent <= 0 || percent > 100) {
    throw httpError(400, `${label} percentage must be greater than 0 and no more than 100.`);
  }
  return percent;
}

function validateExpectedUpdatedAt(value) {
  if (value == null || value === '') {
    throw httpError(400, 'Reload discount settings before saving.');
  }
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < 0) {
    throw httpError(400, 'Reload discount settings before saving.');
  }
  return parsed;
}

function validateRuleInput(input) {
  const name = String(input?.name || '').trim().replace(/\s+/g, ' ');
  if (!name) throw httpError(400, 'Enter a discount name.');
  if (name.length > 60) throw httpError(400, 'Discount name must be 60 characters or fewer.');
  if (['senior', 'senior citizen', 'pwd', 'free drink reward'].includes(name.toLowerCase())) {
    throw httpError(400, 'That discount name is reserved.');
  }
  const scope = String(input?.scope || '').toLowerCase();
  if (!['item', 'order'].includes(scope)) {
    throw httpError(400, 'Discount scope must be item or order.');
  }
  if (typeof input?.requiresReference !== 'boolean') {
    throw httpError(400, 'Reference requirement must be true or false.');
  }
  if (typeof input?.active !== 'boolean') {
    throw httpError(400, 'Active must be true or false.');
  }
  const sortOrder = Number(input?.sortOrder ?? 0);
  if (!Number.isSafeInteger(sortOrder) || sortOrder < 0 || sortOrder > 10000) {
    throw httpError(400, 'Display order must be a whole number from 0 to 10,000.');
  }
  return {
    name,
    percent: validatePercent(input?.percent),
    scope,
    requiresReference: input.requiresReference,
    active: input.active,
    sortOrder
  };
}

function publicRule(row) {
  return {
    id: row.id,
    name: row.name,
    percent: Number(row.percent),
    scope: row.scope,
    requiresReference: Boolean(row.requires_reference),
    active: Boolean(row.active),
    sortOrder: Number(row.sort_order),
    createdAt: Number(row.created_at),
    updatedAt: Number(row.updated_at)
  };
}

function createDiscountService(db, options = {}) {
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

  async function recordChange(client, entityType, entityId, payload) {
    await client.query(`INSERT INTO sync_change
      (branch_id, entity_type, entity_id, operation, payload, device_id, created_at)
      VALUES ($1,$2,$3,'upsert',$4,NULL,$5)`,
      [branchId, entityType, entityId, payload, clock()]);
  }

  async function getSettings(client = db) {
    const settingsResult = await client.query(`SELECT senior_discount_percent, pwd_discount_percent,
        discount_settings_updated_at
      FROM store_settings WHERE id='store' LIMIT 1`);
    const settings = settingsResult.rows[0] || {
      senior_discount_percent: 20,
      pwd_discount_percent: 20,
      discount_settings_updated_at: 0
    };
    const rules = await client.query(`SELECT * FROM discount_rule
      ORDER BY sort_order ASC, LOWER(name) ASC`);
    return {
      seniorPercent: Number(settings.senior_discount_percent),
      pwdPercent: Number(settings.pwd_discount_percent),
      updatedAt: Number(settings.discount_settings_updated_at),
      customDiscounts: rules.rows.map(publicRule)
    };
  }

  async function updateBenefits(input) {
    const seniorPercent = validatePercent(input?.seniorPercent, 'Senior Citizen discount');
    const pwdPercent = validatePercent(input?.pwdPercent, 'PWD discount');
    const expected = validateExpectedUpdatedAt(input?.expectedUpdatedAt);
    return transaction(async client => {
      const current = await client.query(
        "SELECT discount_settings_updated_at FROM store_settings WHERE id='store' FOR UPDATE"
      );
      if (!current.rowCount) throw httpError(409, 'Store settings are not available. Reload and try again.');
      if (Number(current.rows[0].discount_settings_updated_at) !== expected) {
        throw httpError(409, 'Discount settings changed on another screen. Reload before saving.');
      }
      const updatedAt = Math.max(clock(), expected + 1);
      const result = await client.query(`UPDATE store_settings SET
          senior_discount_percent=$1, pwd_discount_percent=$2, discount_settings_updated_at=$3
        WHERE id='store' RETURNING *`, [seniorPercent, pwdPercent, updatedAt]);
      await recordChange(client, 'store_settings', 'store', result.rows[0]);
      return getSettings(client);
    });
  }

  async function createRule(input) {
    const value = validateRuleInput(input);
    return transaction(async client => {
      const idBase = normalizeId(value.name) || 'discount';
      let id = idBase;
      if ((await client.query('SELECT 1 FROM discount_rule WHERE id=$1', [id])).rowCount) {
        id = `${idBase}-${randomId()}`;
      }
      const timestamp = clock();
      try {
        const result = await client.query(`INSERT INTO discount_rule
            (id,name,percent,scope,requires_reference,active,sort_order,created_at,updated_at)
          VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$8) RETURNING *`,
          [id, value.name, value.percent, value.scope, value.requiresReference, value.active, value.sortOrder, timestamp]);
        await recordChange(client, 'discount_rule', id, result.rows[0]);
        return publicRule(result.rows[0]);
      } catch (error) {
        if (error.code === '23505') throw httpError(409, 'A discount with that name already exists.');
        throw error;
      }
    });
  }

  async function updateRule(id, input) {
    const value = validateRuleInput(input);
    const expected = validateExpectedUpdatedAt(input?.expectedUpdatedAt);
    return transaction(async client => {
      const found = await client.query('SELECT * FROM discount_rule WHERE id=$1 FOR UPDATE', [id]);
      if (!found.rowCount) throw httpError(404, 'Discount was not found.');
      if (Number(found.rows[0].updated_at) !== expected) {
        throw httpError(409, 'This discount changed on another screen. Reload before saving.');
      }
      try {
        const result = await client.query(`UPDATE discount_rule SET
            name=$2, percent=$3, scope=$4, requires_reference=$5, active=$6,
            sort_order=$7, updated_at=$8
          WHERE id=$1 RETURNING *`,
          [
            id, value.name, value.percent, value.scope, value.requiresReference,
            value.active, value.sortOrder, Math.max(clock(), expected + 1)
          ]);
        await recordChange(client, 'discount_rule', id, result.rows[0]);
        return publicRule(result.rows[0]);
      } catch (error) {
        if (error.code === '23505') throw httpError(409, 'A discount with that name already exists.');
        throw error;
      }
    });
  }

  return { getSettings, updateBenefits, createRule, updateRule };
}

module.exports = {
  createDiscountService,
  validatePercent,
  validateExpectedUpdatedAt,
  validateRuleInput,
  publicRule
};
